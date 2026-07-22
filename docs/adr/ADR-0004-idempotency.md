# ADR-0004: Idempotency key design and retention

- Status: Accepted
- Date: 2026-07-22
- Deciders: project owner + planning session

## Context and problem statement

Every money-moving endpoint (`POST /transfers`, `POST /journal-entries`,
`POST /journal-entries/{id}/reversal`) creates a journal entry inside one local ACID transaction
([PLAN.md §5, §6](../PLAN.md)). The failure that idempotency must survive is the *ambiguous
timeout*: the client sends a posting, the server commits it, and the response is lost. The client
cannot distinguish "never happened" from "happened, reply lost" — its only safe move is to retry.
Without protection, that retry moves the money twice; in a ledger, a double-post is silent data
corruption that only reconciliation against an external system would ever find.

We therefore require a client-supplied `Idempotency-Key` header on every money-moving endpoint
(`POST /transfers`, `POST /journal-entries`, `POST /journal-entries/{id}/reversal`)
([PLAN.md §1](../PLAN.md)). This ADR decides three coupled sub-questions:

1. **Scope** — what makes a key unique: globally, per principal, or per (principal, endpoint)?
2. **Conflict detection** — how to tell a legitimate replay (same request again) from a client
   bug (same key, different request)?
3. **Retention** — how long the service must remember a key, and what guarantee survives if
   records are ever purged.

The IETF httpapi working group draft "The Idempotency-Key HTTP Header Field"
(draft-ietf-httpapi-idempotency-key-header, latest revision -07 of 2025-10-15, Standards Track
intent; the draft has expired as of April 2026 without replacement, so it is guidance rather than
a standard) defines the header, recommends `422` for key reuse with a different payload, and
leaves scope and retention policy to the resource to define and publish. We align with it where
its recommendations fit a ledger and document the one place we deviate.

## Decision drivers

- **Never double-post.** The guarantee must hold under retries, crashes mid-request, concurrent
  duplicate requests, and — looking ahead — even after old idempotency records are purged.
- **Surface client bugs, don't mask them.** Reusing a key with a different payload is a defect in
  the caller; the ledger should reject loudly, not absorb silently.
- **No false conflicts.** A legitimate retry must never be rejected because a different HTTP
  library serialized the same request differently.
- **Auditability.** The mapping key → entry is itself audit data in an append-only ledger.
- **Operational simplicity.** PostgreSQL only — no Redis, no cache tier; correctness must not
  depend on a second store being in sync.
- **Provability.** The semantics must be expressible as invariants I8/I9 and the M5 hammer test
  ([TEST-STRATEGY.md](../TEST-STRATEGY.md)).

## Considered options

- **Scope**: (1a) globally unique key · (1b) unique per (principal, key), global across write
  endpoints — chosen · (1c) unique per (principal, endpoint, key)
- **Conflict detection**: (2a) none, first write wins · (2b) hash of raw request bytes ·
  (2c) hash of the canonicalized parsed command — chosen
- **Retention**: (3a) keep records indefinitely — chosen for v1 · (3b) TTL + purge, with a
  permanent entry-level unique index as backstop — designed future path · (3c) TTL + purge with
  nothing permanent — rejected

## Decision outcome

**Chosen: `Idempotency-Key` header, scoped per (authenticated principal, key) across all write
endpoints; conflicts detected by SHA-256 over the canonicalized parsed command; the
`idempotency_record` row written in the same transaction as the posting; a permanent partial
unique index on `journal_entry(created_by, idempotency_key)` as the never-expiring backstop;
indefinite retention in v1 with the purge path designed but disabled.**

Decisive reasons:

1. **One transaction, one truth.** Because the `idempotency_record` insert, the
   `journal_entry`/`posting` inserts, and the `account_balance` updates commit atomically, there
   is no crash window in which the effect exists without its record or vice versa. Correctness
   never depends on reconciling two stores.
2. **The database arbitrates races.** PostgreSQL blocks an insert that may conflict with an
   uncommitted row under a unique index until the competing transaction commits or aborts
   (PostgreSQL 17 docs, §62.5 "Index Uniqueness Checks": "the would-be inserter must wait to see
   if that transaction commits"). Two concurrent requests with the same key therefore serialize
   on the index itself: the loser observes the winner's committed record and replays or conflicts
   — a double post is structurally impossible, not merely unlikely.
3. **The backstop outlives the bookkeeping.** The partial unique index
   `journal_entry(created_by, idempotency_key) WHERE idempotency_key IS NOT NULL`
   ([PLAN.md §4.3](../PLAN.md)) lives on the entry itself, which is kept forever. Even a future
   purge of `idempotency_record` can degrade only the *diagnostics* (replay-vs-conflict
   discrimination), never the *safety* (at most one entry per key, ever).

### Mechanics

- **Request flow.** Parse and validate the body into a command object; compute
  `request_hash = SHA-256(canonical(command))`; open the posting transaction; look up
  `idempotency_record` by `(created_by, idem_key)`. Found + equal hash → replay: `200`, stored
  original response body, `Idempotency-Replayed: true`. Found + different hash → `422`
  RFC 9457 problem, no side effects. Not found → execute the posting (locking per
  [ADR-0003](ADR-0003-concurrency-control.md)), insert the record, commit.
- **Canonical form** (frozen for v1; guarded by a golden-file test): JSON with fields in the
  command type's declared order, no insignificant whitespace, amounts as plain integers in minor
  units ([ADR-0001](ADR-0001-money-representation.md)), currency codes uppercase, UUIDs
  lowercase, UTF-8, postings in request order. The idempotency key itself and all transport
  headers are excluded. Leg order is preserved deliberately: treating reordered legs as "the same
  command" would need semantic equivalence rules; a false conflict (`422`, client inspects) is
  cheaper than a wrong guess.
- **Concurrent duplicates.** The loser's insert blocks on the unique index; when the winner
  commits, the loser's transaction receives a uniqueness violation and rolls back (clean — it is
  all one transaction), and the handler re-reads the now-committed record in a fresh transaction
  and answers replay or conflict. If the winner aborts, the loser proceeds as a first attempt.
  This deviates from the IETF draft, which says a resource SHOULD answer `409` while the original
  is in flight; blocking briefly and answering definitively saves the client a retry loop and is
  permitted (SHOULD, not MUST). Documented in the API docs as required by the draft.
- **Only successful outcomes are recorded.** A rejected posting (unbalanced, overdraft, frozen
  account, …) writes nothing, so a retry with the same key re-executes and may legitimately
  succeed once the obstacle is removed. Stripe records failures too (replaying even `500`s,
  though not parameter-validation failures); for a ledger the rollback guarantees the failure had
  zero side effects, so pinning it buys nothing and would block legitimate recovery. Cost: the
  response for a given key is not immutable over time (`422` now, `200` after a retry) — noted in
  the API documentation.
- **Retention v1.** Records are kept indefinitely. `expires_at` is populated
  (`created_at` + configurable TTL, default 90 days) but the purge job ships disabled
  (`ledger.idempotency.purge.enabled=false`). The purge design, for when volume demands: a
  scheduled batched delete — `DELETE FROM idempotency_record WHERE ctid IN (SELECT ctid FROM
  idempotency_record WHERE expires_at < now() LIMIT 1000)` in a loop — using the
  `idempotency_record(expires_at)` index from [PLAN.md §4.3](../PLAN.md). Enabling it is a
  configuration change, not a migration.

### Consequences

Positive:

- Exactly-once posting per (principal, key) is enforced by the database, provable by tests, and
  survives crashes at any point (single-transaction atomicity) and races (unique-index blocking).
- Client bugs (key reuse with a changed payload) surface immediately as `422` instead of
  corrupting the books silently.
- The key → entry mapping is permanent audit data: for any entry one can answer "which request
  created this, with which payload hash, when".
- No infrastructure beyond PostgreSQL; the idempotency store cannot be "out of sync" with the
  ledger because it *is* the ledger's transaction.

Negative — real costs, accepted:

- Every posting pays one extra row insert plus maintenance of two unique indexes on append-heavy
  tables. Measured by `ledger.posting.duration`; acceptable at portfolio scale.
- `idempotency_record` grows forever in v1 — one row (including a `jsonb` response body) per
  successful write. Bounded by entry volume, which is itself retained forever, but the response
  bodies make these rows fatter than postings. The purge path exists for exactly this.
- A blocked concurrent duplicate holds a connection and a request thread for the duration of the
  winner's transaction (bounded by posting latency; observed in the M5 hammer test).
- The canonical serialization is part of the persisted contract: changing it can turn old replays
  into false conflicts, so it is frozen and any change needs explicit migration reasoning.
- Clients must generate fresh high-entropy keys (UUIDs) per logical operation; a "natural" key
  such as an order ID cannot be reused across different operations against the same principal.

### Proof

From [TEST-STRATEGY.md](../TEST-STRATEGY.md), landing in M4/M5:

- **I8 — replay identity** (integration, Testcontainers PostgreSQL): posting the same
  (principal, key, payload) twice yields exactly one `journal_entry` row ever; the second
  response is `200` with the original body, the same entry id, and `Idempotency-Replayed: true`;
  balances change exactly once (asserted through the M3 query surface).
- **I9 — conflict rejection** (property test on the in-repo harness,
  [ADR-0005](ADR-0005-property-testing-tooling.md)): for arbitrary canonically-distinct command
  pairs under one key, the second request returns a `422` problem and provably has zero side
  effects — no new `journal_entry`/`posting` rows, balances unchanged. Companion property:
  serializations that differ only in JSON field order or whitespace hash identically and replay
  rather than conflict (no false conflicts).
- **M5 concurrent-duplicate hammer**: many threads fire the same (principal, key, payload)
  simultaneously; exactly one entry exists afterwards; every response is either the creation or a
  replay carrying the same entry id; `ledger.idempotency.replayed` accounts for the rest. This
  exercises the unique-index blocking path end-to-end on real PostgreSQL.

## Pros and cons of the options

### 1a. Globally unique key

- Good: simplest possible lookup; one index.
- Bad: keyspace is shared across principals. One sloppy client using low-entropy keys ("1", "2")
  poisons everyone; a colliding key either fails spuriously for the second principal or — in a
  naive implementation — replays the *first principal's* stored response to the second, a
  cross-tenant information disclosure. Failure mode: correctness and confidentiality now depend
  on every client's random-number hygiene.

### 1b. Per (principal, key), global across write endpoints — chosen

- Good: collisions are confined to the client that caused them; no cross-principal leakage is
  possible by construction. Matches Stripe's model of a single per-account keyspace ("the first
  request made for any given idempotency key"). Because `/transfers` is sugar over
  `/journal-entries`, retrying one logical operation through the other path still cannot create a
  second entry.
- Bad: accidental key reuse across two genuinely different operations yields `422` instead of two
  successes — the client must mint a fresh UUID per logical operation. We count surfacing that
  reuse as a feature, but it is a real integration burden.

### 1c. Per (principal, endpoint, key)

- Good: forgiving to clients that reuse one key across endpoints; narrower index.
- Bad: that forgiveness is precisely the masking we reject: the same key sent to `/transfers` and
  `/journal-entries` would create *two* entries for what the client believed was one operation.
  Endpoint identity is also fuzzy (versioned paths, aliases) and becomes an invisible part of the
  uniqueness contract. Failure mode: a client-side retry wrapper that switches code paths
  double-posts despite doing everything else right.

### 2a. No conflict detection — first write wins

- Good: simplest; replays always answer with the original result; no canonicalization contract.
- Bad: silently masks client bugs. A caller that derives keys from an order ID while amounts
  change (a classic bug) believes it posted N distinct entries; the ledger holds one. Nothing
  fails, so nothing alerts; the loss surfaces months later in the client's own reconciliation.
  The IETF draft explicitly forbids key reuse with a different payload and recommends `422` —
  option 2a cannot express that.

### 2b. Hash of raw request bytes

- Good: detects reuse; trivial to implement (hash before parsing).
- Bad: false conflicts. A retry emitted by a different HTTP client — reordered JSON fields,
  changed whitespace, `100` vs `1.0e2`, unicode escaping — hashes differently and is rejected as
  a conflict even though it is the same command. Failure mode inversion: the client, told its
  legitimate retry "conflicts", mints a new key and re-sends — and *that* double-posts. The
  safety mechanism becomes the cause of the exact failure it exists to prevent.

### 2c. Hash of the canonicalized parsed command — chosen

- Good: hashes what the service will actually act on, so equality means semantic equality of the
  command; formatting differences cannot cause false conflicts; the stored 64-char hex fits
  `idempotency_record.request_hash` as specified in [PLAN.md §4.3](../PLAN.md).
- Bad: the canonical form is a frozen contract (see Consequences); validation must run before the
  idempotency check, so a request that fails parsing gets no idempotency handling at all
  (acceptable: parsing is deterministic and side-effect-free). Slightly more code on the hot path.

### 3a. Keep records indefinitely — chosen for v1

- Good: replay and conflict discrimination work forever — matching an append-only ledger where
  the entries themselves are permanent; the audit trail is complete; no purge job to operate or
  get wrong. Storage is proportional to entry count, which we have already committed to keeping.
- Bad: unbounded table growth including `jsonb` response bodies; at real production volume this
  is the first thing to revisit (hence 3b is designed, not improvised).

### 3b. TTL + purge, permanent entry-level unique index — designed future path

- Good: bounds `idempotency_record` size while the `journal_entry(created_by, idempotency_key)`
  index still makes a double post impossible at any time horizon.
- Bad — the degradation, precisely: after key K is purged, a reuse of K inserts a new entry, hits
  the permanent unique index, and the handler resolves the *existing* entry by
  `(created_by, K)` and returns it as a replay. It can no longer compare `request_hash`, so a
  reuse with a *different* payload also gets the old entry back instead of `422` — replay-vs-
  conflict discrimination is lost for purged keys. The stored response body is also gone, so the
  replay body is reconstructed from the entry resource and may not be byte-identical to the
  original response. Money stays safe; diagnostics degrade. Stripe accepts a stronger version of
  this trade: keys are pruned after they are at least 24 hours old, and reuse after pruning is
  documented to execute "a new request" — tolerable for their clients' seconds-to-hours retry
  windows, but it is exactly failure 3c below, which a standalone ledger must not adopt.
- Bad: one more scheduled job to operate and monitor.

### 3c. TTL + purge with nothing permanent — rejected

- Bad — exact failure sequence: (1) 09:00 — `POST /transfers`, key K; entry E commits; the
  response is lost to a timeout. (2) The client crashes; its durable retry queue holds the
  request. (3) The TTL elapses; the purge deletes the only memory of K. (4) The retry arrives:
  no record, no constraint — the service executes it as new and commits entry E′. The same money
  has moved twice, no error was returned to anyone, and nothing in the service can even flag it:
  detection requires the client's own reconciliation, and correction requires choosing which of
  E/E′ to reverse. A guarantee with an expiry date is not a guarantee; rejected outright.

## References

- IETF httpapi WG, *The Idempotency-Key HTTP Header Field*,
  draft-ietf-httpapi-idempotency-key-header-07 (2025-10-15; expired 2026-04-18):
  <https://datatracker.ietf.org/doc/html/draft-ietf-httpapi-idempotency-key-header-07> —
  status page: <https://datatracker.ietf.org/doc/draft-ietf-httpapi-idempotency-key-header/>
- Stripe API reference, *Idempotent requests* (retention ≥ 24 h, pruning behavior, parameter
  comparison, failure replay): <https://docs.stripe.com/api/idempotent_requests>
- PostgreSQL 17 documentation, §62.5 *Index Uniqueness Checks* (inserter waits on an uncommitted
  conflicting row): <https://www.postgresql.org/docs/17/index-unique-checks.html>
- Brandur Leach, *Implementing Stripe-like Idempotency Keys in Postgres* (idempotency record
  committed atomically with the operation's effects):
  <https://brandur.org/idempotency-keys>
- RFC 9457, *Problem Details for HTTP APIs*: <https://www.rfc-editor.org/rfc/rfc9457>
- Internal: [PLAN.md](../PLAN.md) §4.3 (schema, indexes), §5 (API semantics) ·
  [TEST-STRATEGY.md](../TEST-STRATEGY.md) (I8, I9, M5 hammer) ·
  [ADR-0002](ADR-0002-balance-storage.md) · [ADR-0003](ADR-0003-concurrency-control.md)
