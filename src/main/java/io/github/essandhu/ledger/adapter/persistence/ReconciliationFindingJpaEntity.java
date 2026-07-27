package io.github.essandhu.ledger.adapter.persistence;

import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;
import org.springframework.data.domain.Persistable;

import io.github.essandhu.ledger.application.port.out.ReconciliationFinding;
import io.github.essandhu.ledger.domain.model.AccountId;

/**
 * JPA face of one {@code reconciliation_finding} row (V5, I15) — one drifted account in one
 * sweep, written once and kept as audit history (ADR-0002: flag, never repair).
 * {@code @Immutable} + {@link Persistable} for the same reasons as {@code PostingJpaEntity}:
 * Hibernate never dirty-checks these rows, and every instance {@link #fromDomain} builds is
 * new — rows are written exactly once, so a merge probe per finding would be waste. No
 * equals/hashCode: entities are never held in sets or compared, and dead code is a coverage
 * and correctness liability.
 */
@Entity
@Table(name = "reconciliation_finding")
@Immutable
class ReconciliationFindingJpaEntity implements Persistable<UUID> {

    @Id
    private UUID id;

    private UUID runId;

    private UUID accountId;

    private long snapshotBalance;

    private long snapshotCount;

    private long computedBalance;

    private long computedCount;

    private long delta;

    protected ReconciliationFindingJpaEntity() {
        // JPA
    }

    static ReconciliationFindingJpaEntity fromDomain(ReconciliationFinding finding) {
        ReconciliationFindingJpaEntity entity = new ReconciliationFindingJpaEntity();
        entity.id = finding.id();
        entity.runId = finding.runId();
        entity.accountId = finding.accountId().value();
        entity.snapshotBalance = finding.snapshotBalance();
        entity.snapshotCount = finding.snapshotCount();
        entity.computedBalance = finding.computedBalance();
        entity.computedCount = finding.computedCount();
        entity.delta = finding.delta();
        return entity;
    }

    /** Rebuilds through the record constructor — delta consistency and the records-drift rule
     * re-run on every load, so database corruption surfaces loudly. */
    ReconciliationFinding toDomain() {
        return new ReconciliationFinding(id, runId, new AccountId(accountId), snapshotBalance,
                snapshotCount, computedBalance, computedCount, delta);
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        // True by construction: only fromDomain instances are ever saved; loaded instances
        // are read-only faces of write-once rows that no code path saves back.
        return true;
    }
}
