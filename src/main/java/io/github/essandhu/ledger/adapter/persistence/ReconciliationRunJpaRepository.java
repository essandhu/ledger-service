package io.github.essandhu.ledger.adapter.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Runs, plus the reconciliation scan itself: the comparison and integrity queries are anchored
 * here rather than on the balance/posting repositories because they are M6's queries — the
 * posting engine's repositories stay exactly the single-lock-site story ADR-0003 documents,
 * with no reconciliation reader mixed into them.
 */
interface ReconciliationRunJpaRepository extends JpaRepository<ReconciliationRunJpaEntity, UUID> {

    /** Native comparison row for the scan queries: aliases quoted in the SQL so they bind to
     * these camelCase getters by exact name. */
    interface ComparisonRow {

        UUID getAccountId();

        long getSnapshotBalance();

        long getSnapshotCount();

        long getComputedBalance();

        long getComputedCount();
    }

    /**
     * ADR-0002's detector, literally: each account's snapshot pair next to the pair recomputed
     * from its postings, in ONE statement — a single statement's READ COMMITTED snapshot is
     * consistent with the atomic posting transactions it observes, so no lock is taken and no
     * false positive is possible under live traffic. LATERAL, not a big GROUP BY: the outer
     * scan walks the account_balance primary key in the database's uuid order and stops at
     * LIMIT, and each account's aggregate is one range scan on the {@code
     * posting_account_statement (account_id, …)} prefix. SUM aggregates in numeric; the final
     * bigint CAST always fits because Σ postings is a once-committed balance (the
     * sumPostingsAsOf argument).
     */
    @Query(value = """
            SELECT ab.account_id AS "accountId", ab.balance AS "snapshotBalance",
                   ab.posting_count AS "snapshotCount", p."computedBalance", p."computedCount"
            FROM account_balance ab,
                 LATERAL (SELECT CAST(COALESCE(SUM(amount), 0) AS bigint) AS "computedBalance",
                                 COUNT(*) AS "computedCount"
                          FROM posting
                          WHERE account_id = ab.account_id) p
            ORDER BY ab.account_id
            LIMIT :pageSize
            """, nativeQuery = true)
    List<ComparisonRow> compareFirstPage(@Param("pageSize") int pageSize);

    /**
     * Resumed comparison page: same statement plus the keyset predicate (the statement-cursor
     * precedent — two queries, because a null bind cannot express "no lower bound" for uuid the
     * way ±infinity does for timestamptz). Strictly-greater resume can neither duplicate nor
     * skip: account_id is the primary key, and the ORDER BY is the database's own uuid order.
     */
    @Query(value = """
            SELECT ab.account_id AS "accountId", ab.balance AS "snapshotBalance",
                   ab.posting_count AS "snapshotCount", p."computedBalance", p."computedCount"
            FROM account_balance ab,
                 LATERAL (SELECT CAST(COALESCE(SUM(amount), 0) AS bigint) AS "computedBalance",
                                 COUNT(*) AS "computedCount"
                          FROM posting
                          WHERE account_id = ab.account_id) p
            WHERE ab.account_id > :afterAccountId
            ORDER BY ab.account_id
            LIMIT :pageSize
            """, nativeQuery = true)
    List<ComparisonRow> comparePageAfter(@Param("afterAccountId") UUID afterAccountId,
            @Param("pageSize") int pageSize);

    /**
     * The single set-based finish statement (V5's one UPDATE path): terminal state, stamped
     * only onto a still-RUNNING row — the WHERE guard turns a double finish into a loud
     * zero-row result at the adapter instead of a silently rewritten verdict.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE reconciliation_run
            SET finished_at = :finishedAt, status = :status, accounts_checked = :accountsChecked,
                drift_count = :driftCount, currency_mismatch_count = :currencyMismatchCount,
                posted_at_mismatch_count = :postedAtMismatchCount,
                unbalanced_currency_count = :unbalancedCurrencyCount
            WHERE id = :runId AND status = 'RUNNING'
            """, nativeQuery = true)
    int finishRun(@Param("runId") UUID runId, @Param("finishedAt") Instant finishedAt,
            @Param("status") String status, @Param("accountsChecked") long accountsChecked,
            @Param("driftCount") long driftCount,
            @Param("currencyMismatchCount") long currencyMismatchCount,
            @Param("postedAtMismatchCount") long postedAtMismatchCount,
            @Param("unbalancedCurrencyCount") long unbalancedCurrencyCount);

    /** The failure stamp: FAILED, result columns stay NULL (V5's results_shape — partial
     * counts from an aborted sweep would be a lie with decimals). Same RUNNING guard. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE reconciliation_run
            SET finished_at = :finishedAt, status = 'FAILED'
            WHERE id = :runId AND status = 'RUNNING'
            """, nativeQuery = true)
    int failRun(@Param("runId") UUID runId, @Param("finishedAt") Instant finishedAt);

    /** Native aggregate row for {@link #aggregateFindings}: aliases quoted, bound by name. */
    interface FindingAggregateRow {

        long getDriftCount();

        long getAbsoluteDrift();
    }

    /** One run's drift figures, aggregated where the rows live: COUNT(*) and Σ|delta| (numeric
     * internally, final bigint CAST — an overflow here fails loudly rather than wrapping). */
    @Query(value = """
            SELECT COUNT(*) AS "driftCount",
                   CAST(COALESCE(SUM(ABS(delta)), 0) AS bigint) AS "absoluteDrift"
            FROM reconciliation_finding
            WHERE run_id = :runId
            """, nativeQuery = true)
    FindingAggregateRow aggregateFindings(@Param("runId") UUID runId);

    /** Denormalization re-check 1: postings whose stored currency disagrees with
     * their account's. The domain enforces the match at post time; this re-verifies at rest. */
    @Query(value = """
            SELECT COUNT(*)
            FROM posting p
            JOIN account a ON a.id = p.account_id
            WHERE p.currency <> a.currency
            """, nativeQuery = true)
    long countCurrencyMismatches();

    /** Denormalization re-check 2: postings whose stored posted_at disagrees with
     * their entry header's. */
    @Query(value = """
            SELECT COUNT(*)
            FROM posting p
            JOIN journal_entry e ON e.id = p.entry_id
            WHERE p.posted_at <> e.posted_at
            """, nativeQuery = true)
    long countPostedAtMismatches();

    /** I5 at rest (ADR-0002's Proof section): currencies whose ledger-wide Σ amount is nonzero.
     * SUM aggregates in numeric, so no overflow can hide an imbalance. */
    @Query(value = """
            SELECT COUNT(*)
            FROM (SELECT currency
                  FROM posting
                  GROUP BY currency
                  HAVING SUM(amount) <> 0) unbalanced
            """, nativeQuery = true)
    long countUnbalancedCurrencies();
}
