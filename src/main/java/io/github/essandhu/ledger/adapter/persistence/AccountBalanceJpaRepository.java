package io.github.essandhu.ledger.adapter.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface AccountBalanceJpaRepository extends JpaRepository<AccountBalanceJpaEntity, UUID> {

    /**
     * THE lock acquisition of ADR-0003, native so its two load-bearing clauses appear verbatim
     * in the SQL PostgreSQL executes instead of trusting dialect generation: {@code ORDER BY
     * account_id} — PostgreSQL compares uuid bytewise, the same total order as
     * {@code BalanceRepository.CANONICAL_ORDER} — and {@code FOR UPDATE}. The ORDER BY is
     * deadlock freedom itself: every transaction that locks a set of balance rows acquires
     * them in this one total order, so no cycle can form and no server-side retry loop is
     * needed (ADR-0003; {@code BalanceLockIntegrationTest} pins the order now, the M5 property
     * test will pin it for arbitrary generated inputs). PostgreSQL's documented
     * ORDER-BY-with-FOR-UPDATE caveat — a concurrently updated row can surface out of order
     * after its EvalPlanQual re-read — cannot bite here: the sort key is the immutable primary
     * key, which the bump statement never touches.
     */
    @Query(value = """
            SELECT account_id, balance, posting_count, updated_at
            FROM account_balance
            WHERE account_id IN (:ids)
            ORDER BY account_id
            FOR UPDATE
            """, nativeQuery = true)
    List<AccountBalanceJpaEntity> lockAllInCanonicalOrder(@Param("ids") Collection<UUID> ids);

    /**
     * ADR-0002's literal statement, set-based on purpose: the addition happens in the
     * database, so no stale in-memory snapshot can be flushed over committed postings — the
     * read-modify-write shape ADR-0002 forbids. Returns the touched-row count so the adapter
     * can refuse a silent zero-row bump. The flush-then-clear brackets are the standard
     * bulk-statement discipline: pending INSERTs (the entry and its postings share this
     * transaction, ADR-0002) reach the database first, and the now-stale managed snapshots
     * leave the persistence context after, so no later read in the transaction can see a
     * pre-bump balance through the first-level cache.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE account_balance
            SET balance = balance + :delta,
                posting_count = posting_count + :legs,
                updated_at = :now
            WHERE account_id = :id
            """, nativeQuery = true)
    int applyDelta(@Param("id") UUID id, @Param("delta") long delta, @Param("legs") long legs,
            @Param("now") Instant now);
}
