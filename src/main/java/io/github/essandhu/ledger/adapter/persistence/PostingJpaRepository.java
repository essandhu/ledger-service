package io.github.essandhu.ledger.adapter.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface PostingJpaRepository extends JpaRepository<PostingJpaEntity, UUID> {

    /**
     * One entry's legs, id-ascending — and id order IS leg order: the service allocates
     * posting ids positionally from the UUIDv7 generator ({@code JournalEntry.post} pairs legs
     * with ids BY POSITION), and {@code IdGeneratorConfig} makes sequential ids strictly
     * increasing by construction — a monotone-clamped clock plus atomic generation, because
     * JUG alone regresses across a backwards wall-clock step — so ascending ids reproduce
     * exactly the order the legs were validated and posted in. I11's exactness proof compares
     * reversal legs positionally and the API renders postings in this order, so leg order must
     * survive the round trip. Served by the {@code posting_entry} index.
     */
    List<PostingJpaEntity> findByEntryIdOrderByIdAsc(UUID entryId);

    /** Native aggregate row for {@link #sumPostingsAsOf}: aliases bound by name. */
    interface AsOfAggregateRow {

        long getSum();

        long getCount();
    }

    /**
     * ADR-0002's as-of derivation (I10): Σ amount and COUNT(*) up to the INCLUSIVE cut, an
     * index-range scan on {@code posting_account_statement (account_id, posted_at, id)} —
     * never the snapshot. PostgreSQL's SUM(bigint) aggregates in numeric, so only the final
     * CAST must fit 64 bits, which it always does: every instant cut is an entry boundary
     * (same-entry legs share posted_at) and every entry-boundary prefix is a once-committed
     * balance (JournalRepository.sumPostingsAsOf pins the argument).
     */
    @Query(value = """
            SELECT CAST(COALESCE(SUM(amount), 0) AS bigint) AS sum, COUNT(*) AS count
            FROM posting
            WHERE account_id = :accountId
              AND posted_at <= :at
            """, nativeQuery = true)
    AsOfAggregateRow sumPostingsAsOf(@Param("accountId") UUID accountId, @Param("at") Instant at);

    /**
     * First statement page (PLAN §5): the {@code (from, to]} window with the bounds optional —
     * a null bound is widened to ±infinity INSIDE the SQL, because the CAST around the
     * parameter is what lets PostgreSQL type a null bind (Java-side sentinels are not an
     * option: Instant.MIN/MAX lie outside timestamptz's range). ORDER BY + LIMIT ride the
     * {@code posting_account_statement} index end to end.
     */
    @Query(value = """
            SELECT id, entry_id, account_id, amount, currency, posted_at
            FROM posting
            WHERE account_id = :accountId
              AND posted_at > COALESCE(CAST(:fromExclusive AS timestamptz), '-infinity'::timestamptz)
              AND posted_at <= COALESCE(CAST(:toInclusive AS timestamptz), 'infinity'::timestamptz)
            ORDER BY posted_at, id
            LIMIT :limit
            """, nativeQuery = true)
    List<PostingJpaEntity> statementFirstPage(@Param("accountId") UUID accountId,
            @Param("fromExclusive") Instant fromExclusive,
            @Param("toInclusive") Instant toInclusive, @Param("limit") int limit);

    /**
     * Resumed statement page: same window plus the keyset predicate — a ROW-VALUE comparison,
     * which PostgreSQL evaluates as one index seek on {@code (account_id, posted_at, id)}
     * (and which JPQL cannot express; native SQL is the convention for load-bearing queries
     * anyway). Strictly-greater resume can neither duplicate nor skip: per-account
     * {@code (posted_at, id)} is a total order that only grows at the top (PLAN §4.6).
     */
    @Query(value = """
            SELECT id, entry_id, account_id, amount, currency, posted_at
            FROM posting
            WHERE account_id = :accountId
              AND posted_at > COALESCE(CAST(:fromExclusive AS timestamptz), '-infinity'::timestamptz)
              AND posted_at <= COALESCE(CAST(:toInclusive AS timestamptz), 'infinity'::timestamptz)
              AND (posted_at, id) > (:cursorPostedAt, :cursorId)
            ORDER BY posted_at, id
            LIMIT :limit
            """, nativeQuery = true)
    List<PostingJpaEntity> statementAfterCursor(@Param("accountId") UUID accountId,
            @Param("fromExclusive") Instant fromExclusive,
            @Param("toInclusive") Instant toInclusive,
            @Param("cursorPostedAt") Instant cursorPostedAt, @Param("cursorId") UUID cursorId,
            @Param("limit") int limit);
}
