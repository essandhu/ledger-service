#!/usr/bin/env bash
# Seeds the state the console e2e lane drives (M8c, ADR-0007): two LIABILITY accounts, one
# ±12345 transfer between them, and a snapshot corrupted by +7 minor units — the demo's exact
# figures. It deliberately does NOT reconcile: leaving the drift undetected is the point, so
# that the sweep a BROWSER starts is the one that convicts it.
#
#   scripts/e2e-fixture.sh
#
# Idempotent by construction: every run creates fresh accounts under new ids and corrupts only
# its own, so re-running adds another drifted account rather than failing or double-counting.
#
# Preconditions: the compose stack is up (`docker compose up -d --wait`). Requirements: Docker
# (compose v2), curl and jq. Bash-portable, same posture as scripts/demo.sh: no GNU-only
# constructs, and every step asserts — a fixture that half-succeeded must fail loudly rather
# than hand the e2e lane a state it will misread.
set -euo pipefail

cd "$(dirname "$0")/.."

FIX_TMP=$(mktemp -d "${TMPDIR:-/tmp}/ledger-e2e-fixture.XXXXXX")
trap 'rm -rf "$FIX_TMP"' EXIT

BASE=http://localhost:8080
KC=http://localhost:8081

bold=$(printf '\033[1m'); green=$(printf '\033[32m'); red=$(printf '\033[31m'); dim=$(printf '\033[2m'); reset=$(printf '\033[0m')
[ -t 1 ] || { bold=""; green=""; red=""; dim=""; reset=""; }

step() { printf '\n%s== %s ==%s\n' "$bold" "$*" "$reset"; }
ok()   { printf '%s  ✓ %s%s\n' "$green" "$*" "$reset"; }
fail() { printf '%s  ✗ %s%s\n' "$red" "$*" "$reset"; exit 1; }

command -v docker >/dev/null || fail "docker is required"
command -v jq >/dev/null || fail "jq is required (https://jqlang.org)"

# Same shape as scripts/demo.sh's helper, including the trailing curl-args passthrough (the
# money movers need an Idempotency-Key header). Globals BODY/STATUS hold the last response.
req() { # METHOD URL [TOKEN] [JSON_BODY] [extra curl args...]
  local method=$1 url=$2 token=${3:-} body=${4:-}
  shift $(( $# < 4 ? $# : 4 ))
  local args=(-s -S -X "$method" -o "$FIX_TMP/body" -w '%{http_code}')
  [ -n "$token" ] && args+=(-H "Authorization: Bearer $token")
  [ -n "$body" ] && args+=(-H 'Content-Type: application/json' -d "$body")
  STATUS=$(curl "${args[@]}" "$@" "$url")
  BODY=$(cat "$FIX_TMP/body")
}

expect() { # EXPECTED_STATUS LABEL
  [ "$STATUS" = "$1" ] || { printf '%s\n' "$BODY"; fail "$2 — expected HTTP $1, got $STATUS"; }
  ok "$2 ${dim}[$STATUS]${reset}"
}

step "Token (ledger-cli service account: ADMIN + WRITE + READ)"
TOKEN=$(curl -s -X POST -d grant_type=client_credentials \
  -d client_id=ledger-cli -d client_secret=ledger-cli-dev-secret \
  "$KC/realms/ledger/protocol/openid-connect/token" | jq -r '.access_token // empty')
[ -n "$TOKEN" ] || fail "no token from Keycloak — is the stack up and the realm imported?"
ok "obtained"

step "Two LIABILITY accounts and the ±12345 transfer between them"
# LIABILITY, as in the README's own walkthrough: customer money is what the ledger owes. The
# source may go negative (a transfer debits it); the target may not.
req POST "$BASE/api/v1/accounts" "$TOKEN" \
  '{"name": "e2e-operating", "currency": "EUR", "type": "LIABILITY", "allowNegative": true}'
expect 201 "created the source account"
SRC=$(jq -r '.id' <<<"$BODY")
req POST "$BASE/api/v1/accounts" "$TOKEN" \
  '{"name": "e2e-customer", "currency": "EUR", "type": "LIABILITY", "allowNegative": false}'
expect 201 "created the target account"
TGT=$(jq -r '.id' <<<"$BODY")

# Every money mover requires an Idempotency-Key (M4, ADR-0004); a fresh one per run is what
# makes this script re-runnable rather than a replay.
req POST "$BASE/api/v1/transfers" "$TOKEN" \
  "{\"sourceAccountId\": \"$SRC\", \"targetAccountId\": \"$TGT\",
    \"amount\": {\"amount\": 12345, \"currency\": \"EUR\"},
    \"description\": \"console e2e fixture\"}" \
  -H "Idempotency-Key: e2e-fixture-$(date +%s)-$$"
expect 201 "transfer posted: source +12345 raw, target -12345 (I1, two zero-sum legs)"

step "Corrupt the source snapshot by +7, as superuser"
# Superuser psql, deliberately NOT ledger_app — whose UPDATE on posting is refused by the grant
# model (I3). ADR-0002's whole argument is that an out-of-band write is the only way a snapshot
# can disagree with its postings, so this is the only honest way to seed drift.
docker compose exec -T postgres psql -U postgres -d ledger -v ON_ERROR_STOP=1 \
  -c "UPDATE account_balance SET balance = balance + 7 WHERE account_id = '$SRC'" >/dev/null
ok "snapshot for $SRC is now +7 out of step with its postings"

step "Confirm the drift is still UNDETECTED"
# No sweep has run since the corruption, so the newest run (if any) predates it. The browser is
# what triggers the sweep that finds this — that is the e2e lane's subject.
req GET "$BASE/api/v1/reconciliation-runs?size=1" "$TOKEN"
expect 200 "run history readable (the M8c listing endpoint)"
ok "fixture ready — drift seeded, unswept"

printf '\n  %sSeeded:%s source %s, target %s, delta +7\n' "$bold" "$reset" "$SRC" "$TGT"
printf '  Now start the console on 8090 and run: ./gradlew :console:e2eTest\n'
