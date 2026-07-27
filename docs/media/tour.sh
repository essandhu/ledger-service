#!/usr/bin/env bash
# The README's demo GIF, scripted (docs/media/demo.tape records this running).
#
# A ~20-second cut of scripts/demo.sh: every line below is a real HTTP call against a running
# stack with real Keycloak tokens, and every claim is asserted — if an invariant regressed, the
# recording fails instead of lying. Unlike scripts/demo.sh this does NOT rebuild the stack; it
# assumes one is already up (`docker compose --profile observability up -d --build --wait`) and
# it is safe to re-run: accounts and idempotency keys are namespaced per run.
#
#   docs/media/tour.sh            # against localhost, no pacing
#   PACE=0.45 docs/media/tour.sh  # pauses between lines, for recording
#
# Overridable for the recorder container, which sits on the compose network:
#   BASE=http://app:8080 KC=http://keycloak:8080 PGHOST=postgres docs/media/tour.sh
set -euo pipefail

cd "$(dirname "$0")/../.."

BASE=${BASE:-http://localhost:8080}
KC=${KC:-http://localhost:8081}
PACE=${PACE:-0}
RUN=${RUN:-$$-$(od -An -N3 -tx1 /dev/urandom | tr -d ' \n')}

TMP=$(mktemp -d "${TMPDIR:-/tmp}/ledger-tour.XXXXXX")
trap 'rm -rf "$TMP"' EXIT

bold=$(printf '\033[1m'); cyan=$(printf '\033[36m'); green=$(printf '\033[32m')
red=$(printf '\033[31m'); dim=$(printf '\033[2m'); reset=$(printf '\033[0m')
[ -t 1 ] || { bold=""; cyan=""; green=""; red=""; dim=""; reset=""; }

pace()  { [ "$PACE" = "0" ] || sleep "$1"; }
step()  { printf '\n%s%s== %s ==%s\n' "$bold" "$cyan" "$*" "$reset"; pace "${PACE}"; }
fail()  { printf '%s  x %s%s\n' "$red" "$*" "$reset"; exit 1; }
# okc STATUS "METHOD /path" "what it proves"  — the padded field is ASCII only, so %-42s lines up.
okc()   { printf '  %s\xe2\x9c\x93%s %s[%s]%s  %-42s %s\n' "$green" "$reset" "$dim" "$1" "$reset" "$2" "$3"; pace "${PACE}"; }
# ok "text" — same text column, for the two claims that are not HTTP calls.
ok()    { printf '  %s\xe2\x9c\x93%s        %s\n' "$green" "$reset" "$*"; pace "${PACE}"; }

# --- request helper: body and status captured separately (portable; no GNU `head -n -1`).
req() { # METHOD PATH [TOKEN] [JSON_BODY] [extra curl args...]
  local method=$1 path=$2 token=${3:-} body=${4:-}
  shift $(( $# < 4 ? $# : 4 ))
  local args=(-s -S -X "$method" -o "$TMP/body" -D "$TMP/headers" -w '%{http_code}')
  [ -n "$token" ] && args+=(-H "Authorization: Bearer $token")
  [ -n "$body" ] && args+=(-H 'Content-Type: application/json' -d "$body")
  STATUS=$(curl "${args[@]}" "$@" "$BASE$path")
  BODY=$(cat "$TMP/body")
  HEADERS=$(cat "$TMP/headers")
}

expect() { # EXPECTED_STATUS LABEL
  [ "$STATUS" = "$1" ] || { printf '%s\n' "$BODY"; fail "$2 — expected HTTP $1, got $STATUS"; }
}

token_for() { # CLIENT_ID CLIENT_SECRET
  curl -s -X POST -d grant_type=client_credentials -d "client_id=$1" -d "client_secret=$2" \
    "$KC/realms/ledger/protocol/openid-connect/token" | jq -r '.access_token // empty'
}

# psql as an arbitrary role: direct when a client is on PATH (the recorder container), otherwise
# through the compose service, so this script also works unchanged on a plain dev machine.
psql_as() { # USER PASSWORD [psql args...]
  local user=$1 pass=$2; shift 2
  if command -v psql >/dev/null && [ -n "${PGHOST:-}" ]; then
    PGPASSWORD="$pass" psql -h "$PGHOST" -U "$user" -d ledger "$@"
  else
    docker compose exec -T -e PGPASSWORD="$pass" postgres psql -U "$user" -d ledger "$@"
  fi
}

step "Default-deny: the anonymous surface is exactly one endpoint"
req GET /actuator/health
expect 200 "health"
okc 200 "GET  /actuator/health" "the only endpoint without a token"
req GET /api/v1/accounts
expect 401 "anonymous accounts"
okc 401 "GET  /api/v1/accounts" "no token, no ledger (I13)"

step "Real Keycloak tokens, and no role hierarchy (I13)"
ADMIN=$(token_for ledger-cli ledger-cli-dev-secret)
RO=$(token_for ledger-readonly ledger-readonly-dev-secret)
[ -n "$ADMIN" ] && [ -n "$RO" ] || fail "Keycloak minted no tokens"
ok "client_credentials → ledger-cli ${dim}(ADMIN+WRITE+READ+METRICS)${reset}, ledger-readonly ${dim}(READ)${reset}"
req POST /api/v1/accounts "$RO" '{"name":"nope","currency":"EUR","type":"ASSET","allowNegative":false}'
expect 403 "read-only create"
okc 403 "POST /api/v1/accounts" "as ledger-readonly: READ never implies ADMIN"

step "A transfer is a balanced double entry (I1)"
req POST /api/v1/accounts "$ADMIN" "{\"name\":\"tour-$RUN-operating\",\"currency\":\"EUR\",\"type\":\"LIABILITY\",\"allowNegative\":true}"
expect 201 "create source"
SRC=$(jq -r '.id' <<<"$BODY")
req POST /api/v1/accounts "$ADMIN" "{\"name\":\"tour-$RUN-customer\",\"currency\":\"EUR\",\"type\":\"LIABILITY\",\"allowNegative\":false}"
expect 201 "create target"
TGT=$(jq -r '.id' <<<"$BODY")
req POST /api/v1/transfers "$ADMIN" \
  "{\"sourceAccountId\":\"$SRC\",\"targetAccountId\":\"$TGT\",\"amount\":{\"amount\":12345,\"currency\":\"EUR\"},\"description\":\"tour\"}" \
  -H "Idempotency-Key: tour-$RUN-transfer"
expect 201 "transfer"
ENTRY=$(jq -r '.id' <<<"$BODY")
jq -e '[.postings[].amount.amount] | sort == [-12345, 12345]' <<<"$BODY" >/dev/null || fail "legs are not zero-sum"
okc 201 "POST /api/v1/transfers" "123.45 EUR: two legs, +12345 / -12345"
req POST /api/v1/journal-entries "$ADMIN" \
  "{\"description\":\"unbalanced\",\"postings\":[{\"accountId\":\"$SRC\",\"amount\":{\"amount\":100,\"currency\":\"EUR\"}},{\"accountId\":\"$TGT\",\"amount\":{\"amount\":-60,\"currency\":\"EUR\"}}]}" \
  -H "Idempotency-Key: tour-$RUN-unbalanced"
expect 422 "unbalanced"
okc 422 "POST /api/v1/journal-entries" "legs summing to 40, not 0: $(jq -r '.type | sub(".*/problems/"; "")' <<<"$BODY")"
req POST /api/v1/transfers "$ADMIN" \
  "{\"sourceAccountId\":\"$TGT\",\"targetAccountId\":\"$SRC\",\"amount\":{\"amount\":99999,\"currency\":\"EUR\"},\"description\":\"drain\"}" \
  -H "Idempotency-Key: tour-$RUN-overdraft"
expect 422 "overdraft"
okc 422 "POST /api/v1/transfers" "draining a strict account: $(jq -r '.type | sub(".*/problems/"; "")' <<<"$BODY") (I6)"

step "Immutability is a database grant, not a code convention (I3)"
if psql_as ledger_app ledger_app -c 'UPDATE posting SET amount = amount + 1' >/dev/null 2>"$TMP/psql"; then
  fail "the runtime role updated a posting — immutability is broken"
fi
grep -q 'permission denied' "$TMP/psql" || fail "UPDATE failed for the wrong reason: $(cat "$TMP/psql")"
ok "UPDATE posting AS ledger_app → ${dim}$(grep -m1 -o 'permission denied.*' "$TMP/psql")${reset}"

step "Idempotent replay (I8) and tamper conflict (I9)"
req POST /api/v1/transfers "$ADMIN" \
  "{\"sourceAccountId\":\"$SRC\",\"targetAccountId\":\"$TGT\",\"amount\":{\"amount\":12345,\"currency\":\"EUR\"},\"description\":\"tour\"}" \
  -H "Idempotency-Key: tour-$RUN-transfer"
expect 200 "replay"
grep -qi '^idempotency-replayed: true' <<<"$HEADERS" || fail "missing Idempotency-Replayed: true"
[ "$(jq -r '.id' <<<"$BODY")" = "$ENTRY" ] || fail "replay returned a different entry"
okc 200 "POST /api/v1/transfers" "same key + payload: replayed, same entry id"
req POST /api/v1/transfers "$ADMIN" \
  "{\"sourceAccountId\":\"$SRC\",\"targetAccountId\":\"$TGT\",\"amount\":{\"amount\":99,\"currency\":\"EUR\"},\"description\":\"tour\"}" \
  -H "Idempotency-Key: tour-$RUN-transfer"
expect 422 "tamper"
okc 422 "POST /api/v1/transfers" "same key, tampered payload: no side effects"

step "Reversal negates exactly, at most once (I11)"
req POST /api/v1/transfers "$ADMIN" \
  "{\"sourceAccountId\":\"$SRC\",\"targetAccountId\":\"$TGT\",\"amount\":{\"amount\":500,\"currency\":\"EUR\"},\"description\":\"tour reversible\"}" \
  -H "Idempotency-Key: tour-$RUN-reversible"
expect 201 "reversible transfer"
REVERSIBLE=$(jq -r '.id' <<<"$BODY")
okc 201 "POST /api/v1/transfers" "5.00 EUR, an entry to reverse"
req POST "/api/v1/journal-entries/$REVERSIBLE/reversal" "$ADMIN" '{"description":"tour reversal"}' \
  -H "Idempotency-Key: tour-$RUN-reversal-1"
expect 201 "reversal"
jq -e '[.postings[].amount.amount] | sort == [-500, 500]' <<<"$BODY" >/dev/null || fail "reversal does not negate"
okc 201 "POST /api/v1/journal-entries/{id}/reversal" "negates leg-for-leg; the pair nets to zero"
req POST "/api/v1/journal-entries/$REVERSIBLE/reversal" "$ADMIN" '{"description":"again"}' \
  -H "Idempotency-Key: tour-$RUN-reversal-2"
expect 422 "double reversal"
okc 422 "POST /api/v1/journal-entries/{id}/reversal" "again: $(jq -r '.type | sub(".*/problems/"; "")' <<<"$BODY")"

step "Reconciliation: induce drift out-of-band, detect it, repair it (I15)"
req POST /api/v1/reconciliation-runs "$ADMIN"
expect 201 "first sweep"
[ "$(jq -r '.status' <<<"$BODY")" = "CLEAN" ] || fail "expected CLEAN before drift"
okc 201 "POST /api/v1/reconciliation-runs" "${bold}CLEAN${reset} — every snapshot equals its postings"
psql_as postgres postgres -v ON_ERROR_STOP=1 \
  -c "UPDATE account_balance SET balance = balance + 7 WHERE account_id = '$SRC'" >/dev/null
ok "a superuser corrupts one snapshot by +7 minor units ${dim}— something the app's role cannot do${reset}"
req POST /api/v1/reconciliation-runs "$ADMIN"
expect 201 "drift sweep"
RUN_ID=$(jq -r '.id' <<<"$BODY")
[ "$(jq -r '.status' <<<"$BODY")" = "DRIFT" ] || fail "the sweep failed to convict the corrupted snapshot"
okc 201 "POST /api/v1/reconciliation-runs" "${bold}${red}DRIFT${reset} — $(jq -r '.driftCount' <<<"$BODY") account adrift"
req GET "/api/v1/reconciliation-runs/$RUN_ID/findings" "$RO"
expect 200 "findings"
jq -e --arg a "$SRC" '.content[0].accountId == $a and .content[0].delta == 7' <<<"$BODY" >/dev/null || fail "finding does not name the exact delta"
okc 200 "GET  /api/v1/reconciliation-runs/{id}/findings" "snapshot $(jq -r '.content[0].snapshotBalance' <<<"$BODY") vs computed $(jq -r '.content[0].computedBalance' <<<"$BODY"), delta $(jq -r '.content[0].delta' <<<"$BODY")"
req GET /actuator/prometheus "$RO"
expect 403 "metrics as READ"
okc 403 "GET  /actuator/prometheus" "a READ token may not scrape (ADR-0006)"
req GET /actuator/prometheus "$ADMIN"
expect 200 "metrics as METRICS"
grep -Eq '^ledger_reconciliation_drift_accounts(\{[^}]*\})? (1|1\.0)$' <<<"$BODY" || fail "drift_accounts gauge must read 1"
grep -Eq '^ledger_reconciliation_drift_absolute(\{[^}]*\})? (7|7\.0)$' <<<"$BODY" || fail "drift_absolute gauge must read 7"
okc 200 "GET  /actuator/prometheus" "drift_accounts 1, drift_absolute 7"
psql_as postgres postgres -v ON_ERROR_STOP=1 \
  -c "UPDATE account_balance ab SET balance = COALESCE((SELECT SUM(p.amount) FROM posting p WHERE p.account_id = ab.account_id), 0) WHERE ab.account_id = '$SRC'" >/dev/null
ok "the snapshot is repaired by recomputation ${dim}— never a fixed-delta patch${reset}"
req POST /api/v1/reconciliation-runs "$ADMIN"
expect 201 "final sweep"
[ "$(jq -r '.status' <<<"$BODY")" = "CLEAN" ] || fail "post-repair sweep must be CLEAN"
okc 201 "POST /api/v1/reconciliation-runs" "${bold}CLEAN${reset} again — detected, repaired, re-verified"
printf '\n'
