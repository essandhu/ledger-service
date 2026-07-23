package io.github.essandhu.ledger.application.port.out;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.github.essandhu.ledger.domain.model.AccountBalance;
import io.github.essandhu.ledger.domain.model.AccountId;

/**
 * Driven port for the account_balance snapshot (ADR-0002): the rows the posting engine locks,
 * reads, and bumps, and that account creation seeds at zero.
 *
 * <p>{@link #lockBalances} is THE ONLY lock-taking API in the application — ADR-0003's
 * single-lock-site mitigation: with exactly one code path acquiring row locks, in exactly one
 * order, no interleaving of posting and lifecycle transactions can form a lock cycle, so
 * deadlock freedom is a structural property of the code rather than a runtime hope, and no
 * server-side retry loop is needed in the write path.
 */
public interface BalanceRepository {

    /**
     * PostgreSQL's uuid order: ascending BYTEWISE — unsigned comparison of both halves.
     * Deliberately NOT {@link java.util.UUID#compareTo}, which compares signed longs and
     * disagrees with the database on any id whose first bit is set. The ONE Java-side
     * definition of the database's uuid order: the canonical lock order below derives from
     * it, and so must any other mirror of a database uuid ordering (e.g. the statement
     * keyset's id tiebreak in test fakes) — two hand-rolled copies of this trick would be
     * two chances for one of them to drift back to signed comparison.
     */
    Comparator<UUID> UUID_BYTEWISE_ORDER = Comparator
            .comparing(UUID::getMostSignificantBits, Long::compareUnsigned)
            .thenComparing(UUID::getLeastSignificantBits, Long::compareUnsigned);

    /**
     * The canonical lock order (ADR-0003): account UUIDs in {@link #UUID_BYTEWISE_ORDER}, so
     * the order this layer sorts by and the order the adapter's {@code ORDER BY account_id …
     * FOR UPDATE} locks by are the same total order.
     */
    Comparator<AccountId> CANONICAL_ORDER =
            Comparator.comparing(AccountId::value, UUID_BYTEWISE_ORDER);

    /**
     * {@code SELECT … FOR UPDATE} over the snapshot rows of every given account, blocking
     * until all locks are held (ADR-0003). The caller MUST pass distinct ids sorted by
     * {@link #CANONICAL_ORDER}; the adapter re-sorts defensively inside the query itself (the
     * ORDER BY is load-bearing — the M5 property test asserts it orders arbitrary inputs
     * canonically). Returns the found rows in canonical order; an id without a row is simply
     * absent from the result — the caller reads absence as "this account does not exist",
     * because every real account has a snapshot row by construction (V3 backfill for M1-era
     * accounts, same-transaction {@link #insertZero} from M2 on).
     */
    List<AccountBalance> lockBalances(List<AccountId> idsInCanonicalOrder);

    /**
     * Lock-free point read of the current snapshot row — PLAN §5's O(1) balance endpoint
     * (M3). Deliberately NOT {@link #lockBalances}: this read takes NO lock, so the
     * single-lock-site property above stays intact and a read-only query never queues behind
     * (or ahead of) the posting path. Empty means "no such account" — every real account has
     * a snapshot row by construction, same contract as {@link #lockBalances}.
     */
    Optional<AccountBalance> findCurrent(AccountId accountId);

    /**
     * ADR-0002's literal statement — {@code UPDATE account_balance SET balance = balance +
     * :delta, posting_count = posting_count + :legs, updated_at = :now WHERE account_id = :id}
     * — never an entity-managed read-modify-write. Called only while holding the row's lock
     * via {@link #lockBalances}, in the same transaction that inserts the entry (ADR-0002).
     *
     * <p>DECLARED INVARIANT (relied on by the PLAN §4.6 posted_at clamp): callers pass the
     * entry's {@code posted_at} as {@code now}, so after any applyDelta the row's
     * {@code updated_at} IS the account's last posted_at whenever {@code posting_count > 0}
     * — which is exactly the floor the posting engine clamps against under the lock.
     */
    void applyDelta(AccountId accountId, long delta, long legCount, Instant now);

    /**
     * Seeds the zero snapshot row at account creation, in the same transaction as the account
     * insert (the V2 forward-contract: an account without a balance row would break the lock
     * protocol). Inserting an existing account's row is a programming error.
     */
    void insertZero(AccountId accountId, Instant createdAt);
}
