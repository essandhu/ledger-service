package io.github.essandhu.ledger.application.port.out;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One reconciliation sweep (ADR-0002, invariant I15): its lifecycle status, who asked for it,
 * and — once it has a verdict — the result counts. Operational record, not bookkeeping domain,
 * hence this package (the {@link IdempotencyRecord} precedent); the in-port queries return it
 * directly rather than duplicating an identical view record.
 *
 * <p>The compact constructor mirrors V5's CHECK constraints exactly, so a row that violates the
 * run's shape rules fails loudly on load (the JPA-entity {@code toDomain} rationale: database
 * corruption surfaces, never flows).
 */
public record ReconciliationRun(
        UUID id,
        Instant startedAt,
        Optional<Instant> finishedAt,
        Status status,
        String triggeredBy,
        Optional<Results> results) {

    /** RUNNING until the finish update; CLEAN/DRIFT is the verdict; FAILED died without one. */
    public enum Status { RUNNING, CLEAN, DRIFT, FAILED }

    /**
     * The counts a finished sweep reports: accounts compared, drifted accounts (finding rows),
     * the two denormalization re-verifications (PLAN §4.3), and the I5 global per-currency
     * zero-sum re-check (ADR-0002's Proof section).
     */
    public record Results(
            long accountsChecked,
            long driftCount,
            long currencyMismatchCount,
            long postedAtMismatchCount,
            long unbalancedCurrencyCount) {

        public Results {
            if (accountsChecked < 0 || driftCount < 0 || currencyMismatchCount < 0
                    || postedAtMismatchCount < 0 || unbalancedCurrencyCount < 0) {
                throw new IllegalArgumentException("result counts must be >= 0, got " + this);
            }
        }

        /** True when anything at all is inconsistent — the CLEAN/DRIFT verdict, derived. */
        public boolean anyDrift() {
            return driftCount > 0 || currencyMismatchCount > 0 || postedAtMismatchCount > 0
                    || unbalancedCurrencyCount > 0;
        }
    }

    public ReconciliationRun {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(finishedAt, "finishedAt");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(triggeredBy, "triggeredBy");
        Objects.requireNonNull(results, "results");
        // V5 reconciliation_run_finished_shape: finished exactly when the status says so.
        if ((status == Status.RUNNING) != finishedAt.isEmpty()) {
            throw new IllegalArgumentException(
                    "status " + status + " inconsistent with finishedAt " + finishedAt);
        }
        // V5 reconciliation_run_results_shape: results exactly when there is a verdict.
        boolean verdict = status == Status.CLEAN || status == Status.DRIFT;
        if (verdict != results.isPresent()) {
            throw new IllegalArgumentException(
                    "status " + status + " inconsistent with results " + results);
        }
        // V5 reconciliation_run_verdict_matches_counts: the verdict must match its numbers.
        if (verdict && (status == Status.DRIFT) != results.orElseThrow().anyDrift()) {
            throw new IllegalArgumentException(
                    "verdict " + status + " contradicts counts " + results.orElseThrow());
        }
    }
}
