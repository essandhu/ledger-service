# ADR-0003: Concurrency control for postings

- Status: Accepted
- Date: 2026-07-22
- Deciders: project owner + planning session

## Context and problem statement

A posting transaction touches N ≥ 2 accounts: it validates account status (§4.5 of
[PLAN.md](../PLAN.md)), validates zero-sum per currency, applies the overdraft policy to every
`allow_negative = false` account (natural balance = raw × direction, §4.2), inserts one
`journal_entry` row plus its `posting` rows, and updates the `account_balance` snapshot of every
touched account ([ADR-0002](ADR-0002-balance-storage.md)). Several of these transactions run in
parallel against overlapping account sets.

Without a serialization mechanism per account, four races break the system:

1. **Lost snapshot update** — two transactions read balance 100, each adds 10, both write 110 (I7 violated).
2. **Overdraft race** — two withdrawals of 80 against a balance of 100 each pass the check on the
   stale value and both commit, leaving −60 on an account that forbids negatives (I6 violated).
3. **Status race** — "post to A" and "close A" interleave so a posting lands on an account
   concurrently closed with a supposedly-zero balance.
4. **Deadlock** — transfer A→B and transfer B→A lock their accounts in opposite orders and wait on
   each other until PostgreSQL kills one.

The overdraft check is the crux: it is an inherent **read–check–write** — read the balance,
compare against policy, conditionally reject. Any correct scheme must make that sequence atomic
per account. The question is *how* to serialize per-account work, not *whether* to. This decision
also fixes §4.6 of the plan: `posted_at` is assigned under the per-account serialization point, so
posted-at order equals commit order per account, which is what makes as-of queries exact.

## Decision drivers

- **Provable correctness** is the project's differentiator: the scheme must survive an adversarial
  stress harness (M5), not just typical load.
- **Read–check–write shape** of the overdraft and status checks.
- **Hot accounts are expected**: settlement/clearing accounts (`allow_negative = true`) appear on
  one side of most transfers; they skip the overdraft check but still need lost-update-safe
  snapshot maintenance.
- **Failure-mode simplicity**: retries that re-execute money movement must be provably idempotent;
  fewer moving parts to prove is better.
- **Portability**: prefer standard SQL over PostgreSQL-only mechanisms where the cost is low.
- **Observability**: contention should surface as a measurable queue (`ledger.posting.lock.wait`),
  not as a probabilistic error rate.

## Considered options

- **A.** Pessimistic `SELECT … FOR UPDATE` on `account_balance` rows in canonical (sorted UUID)
  order, at `READ COMMITTED` — **chosen**.
- **B.** Optimistic concurrency: `@Version` column on `account_balance` + retry loop on
  `OptimisticLockException`.
- **C.** `SERIALIZABLE` isolation (PostgreSQL SSI) + retry on `serialization_failure` (SQLSTATE 40001).
- **D.** PostgreSQL advisory locks: `pg_advisory_xact_lock` keyed on account id.

## Decision outcome

**Option A.** A posting transaction resolves its accounts, then executes one
`SELECT … FOR UPDATE` over the `account_balance` rows of **all** touched accounts, ordered by
account UUID ascending. Under the locks it validates lifecycle status, zero-sum per currency, and
overdraft policy; inserts the entry and postings; and updates the snapshots. Isolation stays at
PostgreSQL's default `READ COMMITTED`. Lifecycle transitions (freeze/close in
`ChangeAccountStatusUseCase`) acquire the *same* balance-row lock before checking/changing status,
so a status check under the lock can never race a concurrent transition. The lock lives on the
`account_balance` row, not the `account` row: one lock protocol serializes everything per-account
— postings, snapshot updates, lifecycle — instead of two lock sites acquirable in different orders.

Decisive reasons:

1. **Deadlock freedom by construction.** A single global acquisition order (sorted UUIDs) makes
   circular waits impossible among posting transactions — this is exactly the mitigation the
   PostgreSQL documentation prescribes: "The best defense against deadlocks is generally to avoid
   them by being certain that all applications using a database acquire locks on multiple objects
   in a consistent order" [1]. Options B and C replace deadlocks with aborts, which still demand
   retry machinery.
2. **The read–check–write becomes trivially correct.** Holding an exclusive row lock while
   reading the balance, checking overdraft/status, and writing is the textbook shape for this
   logic. No re-validation loop, no "check again after winning the version race".
3. **Predictable degradation under contention.** Waiters queue on the row lock and are served
   approximately in arrival order; latency on a hot account grows linearly with queue depth and is
   visible in `ledger.posting.lock.wait`. Optimistic and SSI schemes degrade instead into abort
   storms where throughput *drops* as offered load rises.

### Why READ COMMITTED suffices (and stricter would hurt)

Correctness here comes from the explicit locks, not from snapshot semantics. At `READ COMMITTED`,
a `SELECT … FOR UPDATE` that hits a row being concurrently updated waits for that transaction to
finish and then operates on the **latest committed version** of the row [2]. Since every write to
`account_balance` happens under this same lock, the value read under the lock is by construction
current — there is no stale-read window. Raising isolation to `REPEATABLE READ` would actively
hurt: there, a `FOR UPDATE` on a row changed since the transaction's snapshot raises a
serialization error instead of re-reading [2], reintroducing the retry loops this design avoids.

### Consequences

Positive:

- No deadlocks among posting/lifecycle transactions; I17 asserts this under bidirectional hammering.
- No server-side retry loop in the write path — no interleaving produces a serialization or
  deadlock error that requires re-execution. (Postings can still fail *transiently* — lock or
  statement timeouts, connection loss; those surface as retryable errors and clients retry them
  safely under their idempotency key, [ADR-0004](ADR-0004-idempotency.md).) Simpler API
  semantics, simpler tests.
- Fair-ish FIFO queueing on hot accounts; bounded, measurable latency instead of probabilistic failure.
- `posted_at` assigned under the lock ⇒ exact per-account as-of queries (PLAN.md §4.6).
- Portable idiom: `SELECT … FOR UPDATE` on an ordinary query is technically a PostgreSQL
  extension (the SQL standard admits `FOR UPDATE` only as a cursor option), but every major RDBMS
  supports the idiom with equivalent semantics (MySQL/InnoDB, Oracle, SQL Server via `UPDLOCK`),
  so the scheme ports.

Negative — real costs, accepted:

- **Hot-account throughput ceiling.** A single account's posting rate is capped at 1 / (lock hold
  time), where hold time includes entry+posting inserts and commit (fsync). Every transfer through
  a central settlement account serializes on it. Mitigation is measurement first
  (`ledger.posting.lock.wait`), then the escalation path below.
- **Locks held across inserts.** The transaction must stay short: no external calls, no slow work
  under the lock. A stalled transaction holding a hot lock stalls the whole queue behind it and
  ties up pooled connections. Discipline: the use-case service is the only transaction opener.
- **The ordering discipline is load-bearing.** One future code path that locks unordered — or
  updates `account_balance` without the lock — silently reintroduces deadlocks or lost updates.
  Mitigations: a single locking method on `BalanceRepository` (`lockBalances(sortedIds)`) is the
  only write-path API the persistence adapter exposes, and the I17/I7 stress tests run in CI.
- **Pessimism wastes concurrency when contention is absent.** Two transfers over disjoint accounts
  never conflict, so the waste is limited to lock acquisition overhead — small, but nonzero
  compared to option B's optimism.

**Escalation path if a hot account becomes a real bottleneck** (in order):
(1) *sub-account sharding* — split the hot account into k shards, post to `hash(entry) mod k`,
report the sum (balances are already sums over postings, so reads compose); (2) *posting batching*
— coalesce many logical postings into one entry per lock acquisition, amortizing lock and commit
cost; (3) *single-writer-per-account queue* — route all postings for an account through one
in-process worker, removing lock contention entirely. The far end of this spectrum is TigerBeetle,
which executes all transfers on a single core precisely because "the underlying workload is
inherently contentious… Trying to make transactions parallel doesn't make it faster", and gets
throughput back via aggressive batching [5] — the logical conclusion of the same observation this
ADR starts from: per-account money movement is inherently sequential; the choice is only where the
queue lives.

### Proof

Enforced by the invariant suite in [TEST-STRATEGY.md](../TEST-STRATEGY.md); all concurrency tests
run against real PostgreSQL 18.4 via Testcontainers (no H2 anywhere):

- **I7 — no lost updates**: N threads each post one unit deposit to the same account concurrently;
  final snapshot and `SUM(amount)` both equal exactly +N.
- **I6 — no overdraft under any interleaving**: concurrent withdrawals racing over a limited
  balance on an `allow_negative = false` account; the natural balance never goes below zero, and
  rejected requests fail with the overdraft domain error, never with a lock/serialization error.
- **I17 — bidirectional transfers complete without deadlock**: two thread pools hammer A→B and
  B→A transfers simultaneously; zero SQLSTATE `40P01` (`deadlock_detected` [4]) errors, all
  requests terminate. This is the direct regression test for the canonical-ordering discipline —
  it fails within seconds if anyone removes the sort.
- **I4 — snapshot equals sum of postings**: after every stress run, the reconciliation query
  (`snapshot = SUM(amount)` per account, plus `posting_count` watermark) holds for all accounts.
- **M5 stateful model-vs-SUT property suite** (in-repo harness,
  [ADR-0005](ADR-0005-property-testing-tooling.md)): a sequential in-memory model ledger runs the
  same randomized command sequences (post/transfer/freeze/close/reverse) as the real service;
  states must match. With the multi-threaded harness this covers logical correctness and
  interleaving safety; a property test on the persistence adapter additionally asserts the lock
  query orders arbitrary account-UUID inputs canonically.

**Landed M5 (2026-07-26).** Two lanes. The `concurrency`-tagged stress suite runs as its own CI
job (`Concurrency proof`; enrolling it in branch protection's required checks is the one-time
repo setting that finishes TEST-STRATEGY §5's "required"): `DepositRaceConcurrencyTest` (I7),
`OverdraftRaceConcurrencyTest` (I6 — deposits racing withdrawals, final state plus a
full-history prefix walk, which mixed traffic makes the only sound verdict),
`BidirectionalTransferConcurrencyTest` (I17 with I4/I5 and the lock-wait metric asserted), and
`IdempotencyRaceConcurrencyTest` (I8 across all three duplicate families) — all driving the real
HTTP surface on 8 workers against the Hikari-10 pool. The two sequential M5 property suites run
in the DEFAULT lane (they carry only the `integration` tag): `StatefulModelPropertyIntegrationTest`
compares verdict, canonical first offender, and full observable state against `ModelLedger`
after every command, and `LockOrderPropertyIntegrationTest` generalizes
`BalanceLockIntegrationTest`'s fixed adversarial ids to arbitrary generated inputs — each
iteration doubling as an agreement proof between `UUID_BYTEWISE_ORDER` and PostgreSQL's
`ORDER BY account_id`. Honesty note on I17's reach: the SUT takes all balance locks in ONE
statement whose `ORDER BY` imposes the canonical order regardless of bind order, so the hammer
proves deadlock-freedom of the system as built and catches any regression to lock-as-you-go
acquisition, but cannot see removal of the Java-side pre-sorts — those are policed by the unit
fake's sorted-input contract and the lock-order property.

## Pros and cons of the options

### Option A — pessimistic ordered `SELECT … FOR UPDATE` @ READ COMMITTED (chosen)

- Good: deadlock-free by total lock ordering [1]; read–check–write is natural; queueing is
  approximately FIFO with linear, observable latency; no retry code; `FOR UPDATE` conflicts with
  *any* competing write to the row, so even a rogue code path that skips the protocol still
  serializes on the row itself; standard SQL.
- Bad: hot accounts serialize; lock hold time bounds per-account throughput; waiting transactions
  occupy pool connections; correctness of the *deadlock-freedom* claim (not of balances) depends
  on every multi-account path using the shared ordered-locking helper.
- Failure modes: a long-running transaction holding a hot lock stalls the queue (bounded by
  transaction/statement timeouts); an unordered new code path deadlocks — PostgreSQL detects this
  after `deadlock_timeout` (default 1 s [3]) and aborts one victim, so the symptom is loud
  (SQLSTATE 40P01) and I17 catches it in CI rather than production.

### Option B — optimistic `@Version` on `account_balance` + retry

- Good: no explicit lock statements — the conflict is detected at write time; idiomatic JPA
  (a one-annotation mechanism); short transactions. (Not "no blocking": a losing writer still
  waits on the winner's ordinary row lock before its version check fails [2], and multi-row
  flushes must still be ordered to avoid deadlocks — Hibernate does not sort them by default.)
- Bad: every posting writes the balance row, so under contention the write conflict is identical
  to option A's — merely *detected after* the entry and postings were validated and inserted, so
  each losing attempt rolls back a full transaction and redoes all the work. On a hot account, N
  concurrent attempts yield 1 winner and N−1 aborted-and-retried transactions: wasted work grows
  roughly quadratically with concurrency, and throughput collapses exactly when load peaks. The
  retry loop is real infrastructure: cap attempts, back off with jitter, stay exception-safe,
  re-run *all* validation (the overdraft balance is stale on every retry), and remain idempotent
  for anything non-transactional (metrics, logs).
- Failure modes: retry storms and livelock on hot accounts — there is no fairness, so an unlucky
  request can starve indefinitely while fresh requests keep winning; retry-budget exhaustion
  surfaces as a spurious 5xx for a request that was semantically valid; a subtly non-idempotent
  retry path is exactly the class of bug this ledger exists to rule out. Testing every abort point
  in the retry loop is significantly harder than testing "wait, then proceed".

### Option C — SERIALIZABLE isolation + retry on 40001

- Good: the strongest guarantee — PostgreSQL implements true Serializable Snapshot Isolation with
  predicate locking, detecting dangerous read/write dependency patterns among concurrent
  transactions [2]. It protects even interactions nobody thought to lock — a genuine safety net
  for *future* queries that read balances and act on them. No explicit lock code at all.
- Bad: the PostgreSQL docs are explicit that "applications using this level must be prepared to
  retry transactions due to serialization failures" [2] — so option C carries all of option B's
  retry infrastructure and failure modes. Worse for this workload: every posting both reads and
  writes the same hot `account_balance` rows, so the rw-dependency conflicts SSI detects are
  *genuine* and frequent — on a hot account, abort rates rise with concurrency just as in B, with
  less predictable triggering (SSI can also abort on false positives when predicate locks escalate
  to page or relation granularity [2]). The guarantee only spans transactions that all run
  SERIALIZABLE; one READ COMMITTED writer elsewhere silently weakens it. And SSI quality is
  PostgreSQL-specific — "SERIALIZABLE" elsewhere means blocking 2PL, locking reads, or merely
  snapshot isolation — so the design does not port as-is.
- Failure modes: abort storms on hot accounts; retries re-execute the full money-movement path
  (idempotency of retry required); ops burden shifts to monitoring abort rates and tuning
  predicate-lock memory instead of watching a wait-time histogram.

### Option D — advisory locks (`pg_advisory_xact_lock` keyed on account id)

- Good: transaction-scoped advisory locks release automatically at commit/rollback [1]; locking is
  decoupled from any row, so it would work even for entities with no natural lock row; acquisition
  is cheap; ordering discipline works the same way, so deadlock freedom is achievable identically.
- Bad: entirely PostgreSQL-specific. The key space is a single 64-bit `bigint` (or two 32-bit
  ints) [6], but account ids are 128-bit UUIDs — the id must be hashed to 64 bits, admitting
  collisions that cause false contention between unrelated accounts, in a keyspace shared
  database-wide with any other advisory-lock user (schedulers, other apps), inviting cross-purpose
  collisions. Most damning, the docs' own framing: "the system does not enforce their use — it is
  up to the application to use them correctly" [1]. An advisory lock protects only code that
  remembers to take it; a plain `UPDATE account_balance` from any path bypasses it silently,
  whereas row locks fail safe — the update itself conflicts. Finally, the posting transaction
  updates the balance row anyway, so it holds the row lock *in addition to* the advisory lock: two
  lock protocols where one suffices, and since the balance row always exists (created with the
  account), the one scenario advisory locks uniquely enable never occurs in this schema.
- Failure modes: hash-collision contention that is invisible in the schema and miserable to debug;
  a code path that skips the advisory lock corrupts balances with no error at all (violations are
  caught only later by reconciliation); advisory locks consume the shared lock-table memory pool [1].

### Cross-cutting backstop

Independent of the option chosen, the partial unique index
`journal_entry(created_by, idempotency_key)` ([ADR-0004](ADR-0004-idempotency.md)) remains the
final guard against double-posting: even if a concurrency bug let two executions of the same
client request race, the second `INSERT` violates the index and rolls back. The locking protocol
prevents races; the index makes duplicate request execution unable to commit twice regardless.

## References

1. PostgreSQL 17 Documentation, "Explicit Locking" (deadlocks, consistent lock ordering, row-level
   locks, advisory locks) — https://www.postgresql.org/docs/17/explicit-locking.html
2. PostgreSQL 17 Documentation, "Transaction Isolation" (READ COMMITTED re-evaluation on locked
   rows; SSI; the retry obligation) — https://www.postgresql.org/docs/17/transaction-iso.html
3. PostgreSQL 17 Documentation, "Lock Management" (`deadlock_timeout`, default 1 s) —
   https://www.postgresql.org/docs/17/runtime-config-locks.html
4. PostgreSQL 17 Documentation, "PostgreSQL Error Codes" (`40001 serialization_failure`,
   `40P01 deadlock_detected`) — https://www.postgresql.org/docs/17/errcodes-appendix.html
5. TigerBeetle, "Architecture" (single-threaded execution, contention rationale, batching) —
   https://github.com/tigerbeetle/tigerbeetle/blob/main/docs/ARCHITECTURE.md
6. PostgreSQL 17 Documentation, "System Administration Functions — Advisory Lock Functions"
   (`pg_advisory_xact_lock(key bigint)` / `(key1 int, key2 int)`) —
   https://www.postgresql.org/docs/17/functions-admin.html
7. Internal: [PLAN.md](../PLAN.md) §4.2 (sign convention), §4.5 (lifecycle), §4.6 (time), §6
   (consistency summary); [TEST-STRATEGY.md](../TEST-STRATEGY.md) (invariants I4, I6, I7, I17; M5).
