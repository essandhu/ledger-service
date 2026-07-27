#!/usr/bin/env bash
# End-to-end demo from a clean clone (PLAN §9, M7): boots the compose stack and walks every
# guarantee the README advertises — real Keycloak tokens, the role matrix, double-entry
# postings with their rejection vocabulary, DB-enforced immutability, balances/statements,
# idempotent replay, reversals, and the reconciliation story (drift induced out-of-band,
# detected, repaired, re-verified).
#
#   scripts/demo.sh                  # fresh stack (down -v first), leaves it running
#   scripts/demo.sh --observability  # same, plus Prometheus + Grafana (ADR-0006)
#
# Requirements: Docker (compose v2) and jq. Bash-portable: no GNU-only constructs — status
# codes and bodies are captured separately instead of `head -n -1` (which breaks on macOS).
set -euo pipefail

cd "$(dirname "$0")/.."

# Private scratch dir: fixed /tmp names would be symlink-clobberable on shared hosts and
# collide across concurrent runs. Portable to BSD/macOS mktemp.
DEMO_TMP=$(mktemp -d "${TMPDIR:-/tmp}/ledger-demo.XXXXXX")
trap 'rm -rf "$DEMO_TMP"' EXIT

BASE=http://localhost:8080
KC=http://localhost:8081
OBSERVABILITY=false
[ "${1:-}" = "--observability" ] && OBSERVABILITY=true

bold=$(printf '\033[1m'); green=$(printf '\033[32m'); red=$(printf '\033[31m'); dim=$(printf '\033[2m'); reset=$(printf '\033[0m')
[ -t 1 ] || { bold=""; green=""; red=""; dim=""; reset=""; }

step() { printf '\n%s== %s ==%s\n' "$bold" "$*" "$reset"; }
ok()   { printf '%s  ✓ %s%s\n' "$green" "$*" "$reset"; }
fail() { printf '%s  ✗ %s%s\n' "$red" "$*" "$reset"; exit 1; }

command -v docker >/dev/null || fail "docker is required"
command -v jq >/dev/null || fail "jq is required (https://jqlang.org)"

# --- request helper: captures body and status separately (portable; no GNU head tricks).
# Globals BODY/STATUS/HEADERS hold the last response.
req() { # METHOD URL [TOKEN] [JSON_BODY] [extra curl args...]
  local method=$1 url=$2 token=${3:-} body=${4:-}
  shift $(( $# < 4 ? $# : 4 ))
  local args=(-s -S -X "$method" -o "$DEMO_TMP/body" -D "$DEMO_TMP/headers" -w '%{http_code}')
  [ -n "$token" ] && args+=(-H "Authorization: Bearer $token")
  [ -n "$body" ] && args+=(-H 'Content-Type: application/json' -d "$body")
  STATUS=$(curl "${args[@]}" "$@" "$url")
  BODY=$(cat "$DEMO_TMP/body")
  HEADERS=$(cat "$DEMO_TMP/headers")
}

expect() { # EXPECTED_STATUS LABEL
  [ "$STATUS" = "$1" ] || { printf '%s\n' "$BODY"; fail "$2 — expected HTTP $1, got $STATUS"; }
  ok "$2 ${dim}[$STATUS]${reset}"
}

token_for() { # CLIENT_ID CLIENT_SECRET
  curl -s -X POST -d grant_type=client_credentials -d "client_id=$1" -d "client_secret=$2" \
    "$KC/realms/ledger/protocol/openid-connect/token" | jq -r '.access_token // empty'
}

step "Fresh stack (docker compose down -v && up --build --wait; first build takes a few minutes)"
# Teardown names the observability profile unconditionally: a previous --observability run's
# containers would otherwise survive a plain `down` and hold the network.
docker compose --profile observability down -v --remove-orphans >/dev/null 2>&1 || true
profile_args=()
$OBSERVABILITY && profile_args=(--profile observability)
# ${arr[@]+...} guard: macOS ships bash 3.2, where "${arr[@]}" on an empty array trips set -u.
docker compose ${profile_args[@]+"${profile_args[@]}"} up -d --build --wait --wait-timeout 300
ok "PostgreSQL + Keycloak (realm imported) + app are healthy"

step "M0 — anonymous surface is exactly one endpoint"
req GET "$BASE/actuator/health"
expect 200 "GET /actuator/health without a token"
req GET "$BASE/api/v1/accounts"
expect 401 "GET /api/v1/accounts without a token (default-deny, I13)"

step "Tokens — client_credentials against the imported realm"
ADMIN=$(token_for ledger-cli ledger-cli-dev-secret)      # LEDGER_ADMIN+WRITE+READ+METRICS
RO=$(token_for ledger-readonly ledger-readonly-dev-secret) # LEDGER_READ only
[ -n "$ADMIN" ] && [ -n "$RO" ] || fail "Keycloak did not mint tokens (realm import broken?)"
ok "ledger-cli (all roles) and ledger-readonly (READ only) tokens minted"

step "M1 — accounts, lifecycle, and the 403 half of the role matrix (I13)"
req POST "$BASE/api/v1/accounts" "$RO" '{"name":"demo","currency":"EUR","type":"ASSET","allowNegative":false}'
expect 403 "read-only principal may not create accounts (no role hierarchy)"
req POST "$BASE/api/v1/accounts" "$ADMIN" '{"name":"demo-operating","currency":"EUR","type":"LIABILITY","allowNegative":true}'
expect 201 "create operating account (LIABILITY, may go negative)"
SRC=$(jq -r '.id' <<<"$BODY")
req POST "$BASE/api/v1/accounts" "$ADMIN" '{"name":"demo-customer","currency":"EUR","type":"LIABILITY","allowNegative":false}'
expect 201 "create customer account (LIABILITY, strict)"
TGT=$(jq -r '.id' <<<"$BODY")
req POST "$BASE/api/v1/accounts" "$ADMIN" '{"name":"demo-frozen","currency":"EUR","type":"ASSET","allowNegative":true}'
expect 201 "create a third account to freeze"
FROZEN=$(jq -r '.id' <<<"$BODY")
req PATCH "$BASE/api/v1/accounts/$FROZEN" "$ADMIN" '{"status":"FROZEN"}'
expect 200 "freeze it (I12 lifecycle)"

step "M2 — a transfer is a balanced double entry (I1), and every rejection has a name"
req POST "$BASE/api/v1/transfers" "$ADMIN" \
  "{\"sourceAccountId\":\"$SRC\",\"targetAccountId\":\"$TGT\",\"amount\":{\"amount\":12345,\"currency\":\"EUR\"},\"description\":\"demo transfer\"}" \
  -H "Idempotency-Key: demo-transfer-1"
expect 201 "transfer 123.45 EUR operating → customer"
ENTRY=$(jq -r '.id' <<<"$BODY")
req GET "$BASE/api/v1/journal-entries/$ENTRY" "$RO"
expect 200 "read the entry back as READ"
jq -e '[.postings[].amount.amount] | sort == [-12345, 12345]' <<<"$BODY" >/dev/null \
  || fail "legs must be +12345/−12345 (zero-sum, I1)"
ok "two legs, exactly zero-sum: source +12345 (debit), target −12345 (credit)"

req POST "$BASE/api/v1/journal-entries" "$ADMIN" \
  "{\"description\":\"unbalanced\",\"postings\":[{\"accountId\":\"$SRC\",\"amount\":{\"amount\":100,\"currency\":\"EUR\"}},{\"accountId\":\"$TGT\",\"amount\":{\"amount\":-60,\"currency\":\"EUR\"}}]}" \
  -H "Idempotency-Key: demo-unbalanced-1"
expect 422 "unbalanced entry is rejected"
jq -e '.type | endswith("/unbalanced-entry")' <<<"$BODY" >/dev/null || fail "expected problem type unbalanced-entry"
ok "problem type: $(jq -r '.type' <<<"$BODY")"

req POST "$BASE/api/v1/transfers" "$ADMIN" \
  "{\"sourceAccountId\":\"$TGT\",\"targetAccountId\":\"$SRC\",\"amount\":{\"amount\":99999,\"currency\":\"EUR\"},\"description\":\"drain\"}" \
  -H "Idempotency-Key: demo-overdraft-1"
expect 422 "draining the strict account is rejected (I6 overdraft)"

req POST "$BASE/api/v1/transfers" "$ADMIN" \
  "{\"sourceAccountId\":\"$FROZEN\",\"targetAccountId\":\"$SRC\",\"amount\":{\"amount\":1,\"currency\":\"EUR\"},\"description\":\"frozen\"}" \
  -H "Idempotency-Key: demo-frozen-1"
expect 422 "posting to a frozen account is rejected (I12)"

step "M2 — immutability is a database privilege, not a code convention (I3)"
if docker compose exec -T postgres psql -U ledger_app -d ledger \
     -c 'UPDATE posting SET amount = amount + 1' 2>"$DEMO_TMP/psql"; then
  fail "the runtime role updated a posting — immutability broken"
fi
grep -q 'permission denied' "$DEMO_TMP/psql" || fail "UPDATE failed for the wrong reason: $(cat "$DEMO_TMP/psql")"
ok "UPDATE posting as ledger_app → permission denied (asserted on the reason, not just failure)"

step "M3 — balances and statements"
req GET "$BASE/api/v1/accounts/$TGT/balance" "$RO"
expect 200 "current balance of the customer account"
jq -e '.rawBalance.amount == -12345 and .balance.amount == 12345 and .postingCount == 1' <<<"$BODY" >/dev/null \
  || fail "raw −12345 must read as natural +12345 on a LIABILITY"
ok "raw −12345, natural +12345 (sign follows the account's nature), postingCount 1"
req GET "$BASE/api/v1/accounts/$TGT/balance?at=1970-01-01T00:00:00Z" "$RO"
expect 200 "as-of balance at the epoch"
jq -e '.rawBalance.amount == 0 and .postingCount == 0' <<<"$BODY" >/dev/null || fail "epoch balance must be zero (I10)"
ok "as-of epoch: 0 (recomputed from postings, I10)"
req GET "$BASE/api/v1/accounts/$TGT/postings?limit=10" "$RO"
expect 200 "statement page"
CURSOR=$(jq -r '.nextCursor' <<<"$BODY")
req GET "$BASE/api/v1/accounts/$SRC/postings?cursor=$CURSOR" "$RO"
expect 400 "someone else's cursor is refused (account-bound keyset)"

step "M4 — idempotent replay (I8) and tamper conflict (I9)"
req POST "$BASE/api/v1/transfers" "$ADMIN" \
  "{\"sourceAccountId\":\"$SRC\",\"targetAccountId\":\"$TGT\",\"amount\":{\"amount\":12345,\"currency\":\"EUR\"},\"description\":\"demo transfer\"}" \
  -H "Idempotency-Key: demo-transfer-1"
expect 200 "same key, same payload → replay, not a second transfer"
grep -qi '^idempotency-replayed: true' <<<"$HEADERS" || fail "missing Idempotency-Replayed: true"
[ "$(jq -r '.id' <<<"$BODY")" = "$ENTRY" ] || fail "replay must return the ORIGINAL entry id"
ok "Idempotency-Replayed: true, same entry id, balances untouched"
req POST "$BASE/api/v1/transfers" "$ADMIN" \
  "{\"sourceAccountId\":\"$SRC\",\"targetAccountId\":\"$TGT\",\"amount\":{\"amount\":99,\"currency\":\"EUR\"},\"description\":\"demo transfer\"}" \
  -H "Idempotency-Key: demo-transfer-1"
expect 422 "same key, tampered payload → conflict (zero side effects)"
req POST "$BASE/api/v1/transfers" "$ADMIN" \
  "{\"sourceAccountId\":\"$SRC\",\"targetAccountId\":\"$TGT\",\"amount\":{\"amount\":1,\"currency\":\"EUR\"},\"description\":\"no key\"}"
expect 400 "missing Idempotency-Key on a money mover is a shape violation"

step "M2 — reversal negates exactly, at most once (I11)"
req POST "$BASE/api/v1/journal-entries/$ENTRY/reversal" "$ADMIN" '{"description":"demo reversal"}' \
  -H "Idempotency-Key: demo-reversal-1"
expect 201 "reverse the transfer"
REVERSAL=$(jq -r '.id' <<<"$BODY")
jq -e '[.postings[].amount.amount] | sort == [-12345, 12345]' <<<"$BODY" >/dev/null || fail "reversal must negate leg-for-leg"
ok "reversal $REVERSAL negates leg-for-leg; the pair nets to zero"
req POST "$BASE/api/v1/journal-entries/$ENTRY/reversal" "$ADMIN" '{"description":"again"}' \
  -H "Idempotency-Key: demo-reversal-2"
expect 422 "second reversal is rejected (entry-already-reversed)"

step "M6 — reconciliation: induce drift out-of-band, detect it, repair it (I15, ADR-0002)"
req POST "$BASE/api/v1/reconciliation-runs" "$ADMIN"
expect 201 "trigger a sweep as ADMIN"
[ "$(jq -r '.status' <<<"$BODY")" = "CLEAN" ] || fail "expected a CLEAN verdict before drift"
ok "verdict: CLEAN — every snapshot equals its postings (I4), globally zero-sum (I5)"

docker compose exec -T postgres psql -U postgres -d ledger -v ON_ERROR_STOP=1 \
  -c "UPDATE account_balance SET balance = balance + 7 WHERE account_id = '$SRC'" >/dev/null
ok "superuser corrupted the operating snapshot by +7 minor units (something the app role cannot do)"

req POST "$BASE/api/v1/reconciliation-runs" "$ADMIN"
expect 201 "trigger another sweep"
RUN=$(jq -r '.id' <<<"$BODY")
[ "$(jq -r '.status' <<<"$BODY")" = "DRIFT" ] || fail "the sweep must convict the corrupted snapshot"
ok "verdict: DRIFT, driftCount $(jq -r '.driftCount' <<<"$BODY")"
req GET "$BASE/api/v1/reconciliation-runs/$RUN/findings" "$RO"
expect 200 "read the findings as READ (ADMIN cannot — no hierarchy)"
jq -e --arg a "$SRC" '.content[0].accountId == $a and .content[0].delta == 7' <<<"$BODY" >/dev/null \
  || fail "the finding must name the account and the exact delta 7"
ok "finding: account $(jq -r '.content[0].accountId' <<<"$BODY"), snapshot $(jq -r '.content[0].snapshotBalance' <<<"$BODY") vs computed $(jq -r '.content[0].computedBalance' <<<"$BODY"), delta 7"

step "M7 — the gauges need LEDGER_METRICS (ADR-0006), and they fired"
req GET "$BASE/actuator/prometheus" "$RO"
expect 403 "READ token may not scrape metrics (dedicated role, no hierarchy)"
req GET "$BASE/actuator/prometheus" "$ADMIN"
expect 200 "LEDGER_METRICS-bearing token scrapes"
grep -Eq '^ledger_reconciliation_drift_accounts(\{[^}]*\})? (1|1\.0)$' <<<"$BODY" || fail "drift_accounts gauge must read 1"
grep -Eq '^ledger_reconciliation_drift_absolute(\{[^}]*\})? (7|7\.0)$' <<<"$BODY" || fail "drift_absolute gauge must read 7"
ok "ledger_reconciliation_drift_accounts 1, ledger_reconciliation_drift_absolute 7"

step "Repair — recompute the snapshot from its postings, then prove it"
docker compose exec -T postgres psql -U postgres -d ledger -v ON_ERROR_STOP=1 \
  -c "UPDATE account_balance ab SET balance = COALESCE((SELECT SUM(p.amount) FROM posting p WHERE p.account_id = ab.account_id), 0) WHERE ab.account_id = '$SRC'" >/dev/null
ok "snapshot recomputed from postings (never a fixed-delta reversal — recompute is idempotent)"
req POST "$BASE/api/v1/reconciliation-runs" "$ADMIN"
expect 201 "final sweep"
[ "$(jq -r '.status' <<<"$BODY")" = "CLEAN" ] || fail "post-repair sweep must be CLEAN"
ok "verdict: CLEAN again — drift detected, explained, repaired, re-verified"

step "Done"
echo "  Stack is still running:"
echo "    API          $BASE      (Swagger: $BASE/swagger-ui.html — any token)"
echo "    Keycloak     $KC       (admin/admin)"
if $OBSERVABILITY; then
  echo "    Prometheus   http://localhost:9090"
  echo "    Grafana      http://localhost:3000  (anonymous viewer; dashboard: Ledger Service)"
fi
# Profile-aware unconditionally: harmless on a plain run, and it cleans up leftovers from
# any earlier --observability run.
echo "  Tear down:   docker compose --profile observability down -v"
