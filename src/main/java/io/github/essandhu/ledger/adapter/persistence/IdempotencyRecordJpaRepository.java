package io.github.essandhu.ledger.adapter.persistence;

import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data face of {@code idempotency_record}: keyed reads plus the ADR-0004 purge. */
interface IdempotencyRecordJpaRepository
        extends JpaRepository<IdempotencyRecordJpaEntity, IdempotencyRecordJpaEntity.Key> {

    /**
     * One purge batch (ADR-0004 §Mechanics, the recorded design, verbatim shape): ctid-batched
     * so each transaction deletes a bounded slice off the {@code idempotency_record_expiry}
     * index and the money path never queues behind a bulk delete. Native by necessity — JPQL
     * has neither ctid nor DELETE ... LIMIT.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            DELETE FROM idempotency_record
            WHERE ctid IN (SELECT ctid FROM idempotency_record
                           WHERE expires_at < :cutoff
                           LIMIT :batchSize)
            """, nativeQuery = true)
    int deleteExpiredBatch(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);
}
