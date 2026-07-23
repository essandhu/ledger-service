package io.github.essandhu.ledger.application.port.out;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

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
     * The canonical lock order (ADR-0003): account UUIDs ascending BYTEWISE — unsigned
     * comparison of both halves, matching PostgreSQL's uuid ordering, so the order this layer
     * sorts by and the order the adapter's {@code ORDER BY account_id … FOR UPDATE} locks by
     * are the same total order. Deliberately NOT {@link java.util.UUID#compareTo}, which
     * compares signed longs and disagrees with the database on any id whose first bit is set.
     */
    Comparator<AccountId> CANONICAL_ORDER = Comparator
            .comparing((AccountId id) -> id.value().getMostSignificantBits(), Long::compareUnsigned)
            .thenComparing(id -> id.value().getLeastSignificantBits(), Long::compareUnsigned);

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
     * ADR-0002's literal statement — {@code UPDATE account_balance SET balance = balance +
     * :delta, posting_count = posting_count + :legs, updated_at = :now WHERE account_id = :id}
     * — never an entity-managed read-modify-write. Called only while holding the row's lock
     * via {@link #lockBalances}, in the same transaction that inserts the entry (ADR-0002).
     */
    void applyDelta(AccountId accountId, long delta, long legCount, Instant now);

    /**
     * Seeds the zero snapshot row at account creation, in the same transaction as the account
     * insert (the V2 forward-contract: an account without a balance row would break the lock
     * protocol). Inserting an existing account's row is a programming error.
     */
    void insertZero(AccountId accountId, Instant createdAt);
}
