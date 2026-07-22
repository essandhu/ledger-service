# ADR-0002: Balance storage — maintained snapshot with postings as source of truth

- Status: Accepted
- Date: 2026-07-22
- Deciders: project owner + planning session

## Context and problem statement

Every journal entry changes the balances of the accounts it touches. Two consumers need those
balances, with very different tolerances:

1. **The overdraft check inside the posting critical section.** For accounts with
   `allow_negative = false`, the natural balance (`raw × direction(type)`, [PLAN §4.2](../PLAN.md))
   may never go below zero. This check runs while holding the ordered row locks of
   [ADR-0003](ADR-0003-concurrency-control.md) — its cost is added to lock hold time, and lock
   hold time is the reciprocal of a hot account's maximum throughput. It must also be
   **synchronously** correct: an overdraft admitted now and detected later is a broken guarantee,
   not a delayed one.
2. **Client balance reads.** `GET /accounts/{id}/balance` is specified O(1) (PLAN §5);
   as-of-timestamp reads are specified exact per account (PLAN §4.6, invariant I10).

Postings are the append-only, privilege-enforced source of truth (PLAN §4.4): the raw balance of
an account *is defined as* `SUM(amount)` over its postings. The question this ADR answers is
whether the current balance is **derived on demand** from that definition, or **maintained as
redundant state** — and if maintained, with what consistency guarantee and what defense against
the classic failure of redundant state: silent drift.

## Decision drivers

- **Lock hold time**: any work inside the posting critical section multiplies into the
  serialization point of hot accounts (ADR-0003); the overdraft check must not scale with account
  history.
- **Synchronous overdraft guarantee**: the non-negative rule is enforced at commit time, never
  eventually.
- **Verifiable correctness**: redundant state is acceptable only if an automated mechanism
  continuously proves it equal to the source of truth (invariants I4, I15 in
  [TEST-STRATEGY.md](../TEST-STRATEGY.md)).
- **O(1) current-balance API** (PLAN §5) and exact as-of semantics (I10) regardless of storage
  choice.
- **v1 simplicity**: one service, one PostgreSQL; no brokers, no projectors, no extra
  infrastructure whose operational cost exceeds a portfolio project's ability to demonstrate it.

## Considered options

- **A.** Derive-on-read: current balance is always `SUM(amount)` over postings (optionally cached).
- **B.** Maintained transactional snapshot (`account_balance` row updated in the same DB
  transaction as the postings) + scheduled reconciliation. **(chosen)**
- **C.** Periodic checkpoint + tail: balance = last checkpoint + `SUM` of postings since it.
- **D.** Async projection / event-sourced read model (CQRS).

## Decision outcome

**Option B.** Postings remain the append-only source of truth. Each account has one
`account_balance` row (`account_id` PK, `balance`, `posting_count`, `updated_at` — PLAN §4.3),
created with the account at balance 0 and updated **in the same database transaction** that
inserts the entry and its postings, while holding the ordered `FOR UPDATE` locks of ADR-0003:

```sql
UPDATE account_balance
   SET balance       = balance + :delta,        -- sum of this entry's legs on this account
       posting_count = posting_count + :legs,   -- reconciliation watermark
       updated_at    = :now
 WHERE account_id = :id;
```

As-of/historical balances are **always** computed from postings
(`SUM(amount) WHERE account_id = ? AND posted_at <= ?`), never from the snapshot; the snapshot
serves exactly two reads: the current-balance endpoint and the overdraft check under lock.

Decisive reasons:

1. **O(1) inside the critical section.** The overdraft check reads one already-locked row. Lock
   hold time is constant regardless of whether the account has ten postings or ten million, so
   hot-account throughput does not decay as history grows (the failure mode of Option A).
2. **The snapshot row is the lock carrier anyway.** ADR-0003 serializes posting transactions by
   locking `account_balance` rows in canonical UUID order. That row must exist and be exclusively
   locked for every touched account regardless of this decision — so maintaining the balance in it
   costs one UPDATE against a row whose page is already hot and already locked. The marginal cost
   of Option B over "locking-only rows" is nearly zero.
3. **Same-transaction maintenance makes drift a *bug-detector* concern, not a design property.**
   Snapshot and postings commit or roll back atomically; PostgreSQL guarantees no committed state
   exists where one applied and the other did not. Drift therefore cannot arise from crashes,
   races, or replays — only from software defects (wrong delta arithmetic) or out-of-band writes
   (someone with superuser editing `account_balance`). Those are exactly the risks the scheduled
   reconciliation job exists to surface: it recomputes `SUM(amount)` per account, writes
   `reconciliation_run` / `reconciliation_finding` rows, and publishes the
   `ledger.reconciliation.drift.accounts` and `ledger.reconciliation.drift.absolute` gauges
   (PLAN §8). A latent fear becomes a monitored, alertable signal.

**The `posting_count` watermark.** The snapshot carries a count of postings applied to it,
incremented in the same UPDATE. Reconciliation compares `(balance, posting_count)` against
`(SUM(amount), COUNT(*))` computed **in a single SQL statement** joining `account_balance` and
`posting`. Under `READ COMMITTED`, a query "sees a snapshot of the database as of the instant the
query begins to run" [1] — and since snapshot updates commit atomically with posting inserts,
every committed state satisfies I4 (absent bugs), so the one-statement check cannot false-positive
against concurrent live traffic. No quiescing, no locks taken by the job. The count also widens
the detector: a double-applied entry compensated by a missed one of equal amount leaves `balance`
correct but `posting_count` wrong. Finally, the watermark is the natural seam for Option C later —
a checkpoint is precisely a `(balance, posting_count)` pair pinned at a boundary.

Drift handling in v1 is **flag, alert, investigate — never auto-repair**: silently rewriting the
snapshot to match the recomputation would hide the defect that caused the divergence.

This is the same shape production fintech ledgers converge on. Modern Treasury maintains a
current-balance cache that "needs to be updated synchronously when authorizing Entries are written
to an Account" because balance locking "relies on a single database row containing the most
up-to-date balance," and pairs it with verification against source-of-truth entries because cached
reads "may diverge" [2]. Stripe's Ledger is "an immutable log of events" from which balances are
derived, surrounded by monitoring that verifies money movement and flags nonzero clearing
balances [3]. Option B is that architecture at single-node scale.

### Consequences

Positive:

- Overdraft check and current-balance endpoint are O(1); posting lock hold time is independent of
  account history and does not degrade over the ledger's lifetime.
- Drift is structurally impossible through the application write path (single-transaction
  atomicity) and *detected* when it occurs anyway (reconciliation + gauges) — the invariant is
  enforced twice, by design and by proof.
- The snapshot doubles as the ADR-0003 lock carrier: one mechanism serves concurrency control and
  balance maintenance.
- As-of queries stay pure derivations from postings, so historical correctness (I10) never depends
  on snapshot correctness.

Negative — real costs, accepted:

- **Write amplification**: one `UPDATE account_balance` per touched account per entry, roughly
  doubling row writes for a two-leg transfer (2 posting inserts + 1 entry insert + 2 updates).
  Under MVCC every update creates a new row version; hot accounts churn dead tuples. Mitigation:
  `account_balance` is indexed only on its PK and the updated columns are unindexed, so updates
  are HOT-*eligible* — "new index entries are not needed to represent updated rows" and
  intermediate versions can be cleaned during normal operation without vacuum [4]. HOT
  additionally requires free space on the row's page, so a lowered `fillfactor` preserves that
  second precondition; when a page fills anyway, updates fall back to ordinary MVCC updates.
  This is a tuning concern, not a correctness one, and `pg_stat_all_tables`'
  `n_tup_hot_upd / n_tup_upd` ratio exposes it if it ever matters.
- **A new invariant to carry forever**: I4 must hold across every current and future write path.
  Any code that inserts postings without updating the snapshot (or vice versa) is a bug the type
  system cannot catch — only the reconciliation job and the property suite can.
- **A whole subsystem**: Spring Batch job, findings tables, gauges, schedule, admin trigger (M6).
  Option A needs none of it. We are trading code for a bounded critical section.
- **Discipline, not privileges, guards the snapshot**: unlike `journal_entry`/`posting`, the app
  role *must* hold UPDATE on `account_balance`, so DB grants cannot fence it. The guards are the
  port boundary (only `BalanceRepository` touches it, ArchUnit-checkable) and reconciliation.
- The snapshot answers only "now": as-of reads remain O(postings in range) until Option C is
  adopted for archival/acceleration.

### Proof

Automated tests enforcing this decision, by invariant ID
([TEST-STRATEGY.md](../TEST-STRATEGY.md)):

- **I4 — snapshot equals recomputation in every committed state.** Integration tests assert
  `account_balance.balance = SUM(amount)` and `posting_count = COUNT(*)` after every posting
  scenario (including rejected and rolled-back entries: snapshot unchanged). The M5 stateful
  property suite ([ADR-0005](ADR-0005-property-testing-tooling.md)) runs random operation
  sequences against a model and checks I4 after each step; the M5
  concurrency harness re-checks it after parallel mixed transfers.
- **I5 — global conservation.** `SUM(amount) = 0` per currency across all postings, asserted by
  the harness and re-verified by every reconciliation run; combined with I4 it implies the
  snapshots of a single-currency ledger also sum to zero.
- **I10 — as-of consistency.** `asOf(t)` derived from postings equals the snapshot when `t = now`
  under quiesced writes, and `asOf(t2) − asOf(t1)` equals the sum of postings in `(t1, t2]` —
  proving the snapshot and the derivation never diverge as *definitions*.
- **I15 — seeded drift is detected and reported.** An integration test (and the M6 demo) corrupts
  a snapshot via out-of-band superuser SQL — the only way drift can exist — runs the job, and
  asserts a `reconciliation_finding` row with the exact delta plus nonzero
  `ledger.reconciliation.drift.*` gauges; a clean run asserts zero findings (no false positives
  under concurrent load).

## Pros and cons of the options

### A. Derive-on-read (`SUM` per query, optionally cached)

- Good: **cannot drift** — there is no second copy; the read *is* the definition. No
  reconciliation subsystem, no watermark, no I4 to maintain. The honest baseline: a low-volume
  ledger should arguably start here.
- Good: append-only writes only; no UPDATE churn, no dead tuples, simplest possible schema.
- Bad: the overdraft check becomes an aggregate over the account's entire history **inside the
  locked critical section**. The scan is an index range on `posting(account_id, …)` plus heap
  fetches — O(n) in postings per account. At an order-of-magnitude ~1 µs per aggregated row
  (warm cache; heap fetches make it worse), 10k postings cost ~10 ms and 1M postings ~1 s of lock
  hold time — collapsing a hot account to roughly one entry per second, versus sub-millisecond for
  a one-row read. Because history only grows, throughput decays monotonically over the ledger's
  life, and the cost lands at the worst possible place: the serialization point. Modern Treasury
  reports exactly this cliff — summing is fine at thousands of entries but "too slow once we have
  tens or hundreds of thousands of Entries" [2].
- Failure mode: teams bolt on a cache under duress. A cache updated in-transaction *is* Option B
  without its reconciliation discipline; a cache updated asynchronously *is* Option D with its
  staleness hole. Option A does not avoid the choice — it defers it to an incident.

### B. Maintained transactional snapshot + reconciliation — CHOSEN

- Good: O(1) check under lock; snapshot rides the ADR-0003 lock row for near-zero marginal cost;
  atomic commit confines drift to defects and out-of-band writes, which reconciliation then
  detects (argued above).
- Bad: write amplification and MVCC churn on hot rows (mitigated by HOT-eligible updates [4]); a permanent redundancy
  invariant; a reconciliation subsystem to build and operate; snapshot correctness rests on
  discipline (single write path) rather than privileges.
- Failure mode: a defect in delta arithmetic or a bypassing write path corrupts the snapshot until
  the next reconciliation run — bounded by job cadence, surfaced by I15's gauges, and never able
  to corrupt history itself, since postings remain the privilege-protected truth.

### C. Periodic checkpoint + tail

- Good: bounds the scan to postings since the last checkpoint; checkpoints are themselves
  append-only (no hot UPDATE row); the right structure for accelerating as-of queries and for
  archiving old partitions behind a checkpoint (both explicitly out of v1 scope, PLAN §1).
- Bad: overdraft cost is O(tail), bounded but not O(1), and depends on checkpoint cadence — a
  second knob to operate. The checkpointer and the read path must agree exactly on the boundary
  (by `posting_count` watermark or a `posted_at`/id cut), which is subtle under concurrent
  appends. Still needs ADR-0003's lock rows, so it adds moving parts without removing any.
- Failure mode: an off-by-one at the checkpoint boundary double-counts or drops postings —
  a drift-class bug in a design chosen to avoid drift. Verdict: the natural **future**
  optimization layered *under* Option B's as-of path, not a v1 foundation.

### D. Async projection / event-sourced read model (CQRS)

- Good: fully decouples the write path — posting transactions touch no shared balance row; read
  models scale and evolve independently; the natural fit once event publishing (out of v1 scope)
  exists anyway.
- Bad: **the overdraft check against an eventually consistent projection is unsound.** Between
  event append and projection update, concurrent entries validate against stale balances and the
  non-negative guarantee is violated under exactly the concurrency it must survive. Fixing this
  requires a synchronously maintained command-side balance — which is Option B's snapshot,
  reintroduced — plus a broker/outbox, a projector, and lag monitoring on top. Modern Treasury's
  design confirms the split: only their *effective-time* cache is async; the balance used for
  authorization "needs to be updated synchronously" [2].
- Failure mode: projector lag or crash silently widens the staleness window; correctness now
  depends on pipeline health. Verdict: rejected for v1 not because CQRS is wrong, but because for
  the synchronous guarantee it degenerates into Option B plus infrastructure.

## References

1. PostgreSQL 18 documentation — *Transaction Isolation* (Read Committed: a query "sees a snapshot
   of the database as of the instant the query begins to run"; `FOR UPDATE` waiting behavior).
   <https://www.postgresql.org/docs/current/transaction-iso.html>
2. Modern Treasury — *How to Scale a Ledger, Part VI: Concurrency Controls, Performance, and
   More* (synchronous current-balance cache as the row balance locks rely on; derive-on-read "too
   slow once we have tens or hundreds of thousands of Entries"; cache-drift verification).
   <https://www.moderntreasury.com/journal/how-to-scale-a-ledger-part-vi>
3. Stripe — *Ledger: Stripe's system for tracking and validating money movement* (immutable event
   log as source of truth; balances derived from events; discrepancy detection via nonzero
   clearing balances). <https://stripe.dev/blog/ledger-stripe-system-for-tracking-and-validating-money-movement>
4. PostgreSQL 18 documentation — *Heap-Only Tuples (HOT)* (updates that modify no indexed columns
   need no new index entries; intermediate versions cleaned without vacuum; `fillfactor`,
   `pg_stat_all_tables`). <https://www.postgresql.org/docs/current/storage-hot.html>
5. Project documents: [PLAN.md](../PLAN.md) §§4.2–4.6, 5, 6, 8 ·
   [TEST-STRATEGY.md](../TEST-STRATEGY.md) (invariants I4, I5, I10, I15) ·
   [ADR-0003](ADR-0003-concurrency-control.md) (ordered locking on `account_balance` rows).
