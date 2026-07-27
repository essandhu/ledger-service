package io.github.essandhu.ledger.adapter.persistence;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface ReconciliationFindingJpaRepository
        extends JpaRepository<ReconciliationFindingJpaEntity, UUID> {

    /** One run's findings; the adapter passes an id-sorted PageRequest (UUIDv7 ids ⇒ detection
     * order), and Page's count query supplies totalElements. Served by the V5 unique
     * constraint's (run_id, account_id) index prefix. */
    Page<ReconciliationFindingJpaEntity> findByRunId(UUID runId, Pageable pageable);
}
