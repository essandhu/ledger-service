package io.github.essandhu.ledger.adapter.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.springframework.data.domain.Persistable;

import io.github.essandhu.ledger.application.port.out.ReconciliationRun;

/**
 * JPA face of the {@code reconciliation_run} row (V5, I15). The {@code account_balance} shape:
 * deliberately NOT {@code @Immutable} — the row mutates once, from RUNNING to its terminal
 * state — yet the entity has no mutators, because that one mutation goes through the set-based
 * finish/fail statements in {@link ReconciliationRunJpaRepository}, never through a managed
 * instance (the ADR-0002 no-read-modify-write house rule). The only write path here is the
 * one-time RUNNING seed of {@link #running}. No equals/hashCode: entities are never held in
 * sets or compared, and dead code is a coverage and correctness liability.
 *
 * <p>{@link Persistable}: V5 gives this table no version column (nothing races on a run row —
 * one job execution owns it), so the entity states the isNew fact directly; without it,
 * {@code save()} would merge: a probing SELECT per run.
 */
@Entity
@Table(name = "reconciliation_run")
class ReconciliationRunJpaEntity implements Persistable<UUID> {

    @Id
    private UUID id;

    private Instant startedAt;

    private Instant finishedAt;

    @Enumerated(EnumType.STRING)
    private ReconciliationRun.Status status;

    private String triggeredBy;

    private Long accountsChecked;

    private Long driftCount;

    private Long currencyMismatchCount;

    private Long postedAtMismatchCount;

    private Long unbalancedCurrencyCount;

    protected ReconciliationRunJpaEntity() {
        // JPA
    }

    /** The opening RUNNING row — result columns NULL until the finish statement writes them
     * together (V5's results_shape CHECK is the database half of that pact). */
    static ReconciliationRunJpaEntity running(UUID id, Instant startedAt, String triggeredBy) {
        ReconciliationRunJpaEntity entity = new ReconciliationRunJpaEntity();
        entity.id = id;
        entity.startedAt = startedAt;
        entity.status = ReconciliationRun.Status.RUNNING;
        entity.triggeredBy = triggeredBy;
        return entity;
    }

    /** Rebuilds through the record constructor, so the run-shape invariants (finished ⇔ not
     * RUNNING, results ⇔ verdict, verdict ⇔ counts) re-run on every load — database corruption
     * surfaces loudly, not as a quietly-invalid record. */
    ReconciliationRun toDomain() {
        Optional<ReconciliationRun.Results> results = accountsChecked == null
                ? Optional.empty()
                : Optional.of(new ReconciliationRun.Results(accountsChecked, driftCount,
                        currencyMismatchCount, postedAtMismatchCount, unbalancedCurrencyCount));
        return new ReconciliationRun(id, startedAt, Optional.ofNullable(finishedAt), status,
                triggeredBy, results);
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        // True by construction: the only saved instances are fresh RUNNING seeds; loaded
        // instances are read snapshots that no code path saves back (see class javadoc).
        return true;
    }
}
