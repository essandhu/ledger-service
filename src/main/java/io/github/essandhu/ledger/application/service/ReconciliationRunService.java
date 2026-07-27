package io.github.essandhu.ledger.application.service;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

import org.springframework.transaction.annotation.Transactional;

import io.github.essandhu.ledger.application.port.out.ReconciliationRepository;
import io.github.essandhu.ledger.application.port.out.ReconciliationRun;

/**
 * The run-record half of reconciliation (ADR-0002, I15): opens the RUNNING row, and — when the
 * sweep is done — aggregates its findings, performs the run-level integrity re-checks, decides
 * the CLEAN/DRIFT verdict, and writes the single set-based finish update. Driven by the Batch
 * job's listeners (the adapter), which is why each method owns its own short transaction: the
 * listener callbacks run OUTSIDE any step transaction, and the sweep's chunk transactions are
 * the step's own.
 *
 * <p>No {@code @PreAuthorize}: this is job machinery, not a caller-facing use case — the
 * scheduler path has no principal to authorize (the {@link IdempotencyPurgeService} precedent).
 * The verdict decision lives HERE, in the core, so "what counts as drift" is testable without
 * Spring Batch and the database CHECK that mirrors it (V5's verdict_matches_counts) stays a
 * mirror, not the rule.
 */
public class ReconciliationRunService {

    private final ReconciliationRepository reconciliation;
    private final Clock clock;

    public ReconciliationRunService(ReconciliationRepository reconciliation, Clock clock) {
        this.reconciliation = reconciliation;
        this.clock = clock;
    }

    /** Records the sweep's start: the RUNNING row, stamped from the injected Clock. */
    @Transactional
    public void openRun(UUID runId, String triggeredBy) {
        reconciliation.insertRun(runId, clock.instant(), triggeredBy);
    }

    /**
     * Records the sweep's verdict: findings aggregate + integrity re-checks → CLEAN or DRIFT →
     * one finish update, all in one transaction, so a run row never shows a verdict its
     * numbers were not derived under. {@code accountsChecked} comes from the step's read
     * count — the one figure only the job knows.
     */
    @Transactional
    public Closed closeRun(UUID runId, long accountsChecked) {
        ReconciliationRepository.FindingAggregate findings =
                reconciliation.aggregateFindings(runId);
        ReconciliationRepository.IntegrityCounts integrity = reconciliation.integrityCounts();
        ReconciliationRun.Results results = new ReconciliationRun.Results(accountsChecked,
                findings.driftCount(), integrity.currencyMismatchCount(),
                integrity.postedAtMismatchCount(), integrity.unbalancedCurrencyCount());
        ReconciliationRun.Status verdict = results.anyDrift()
                ? ReconciliationRun.Status.DRIFT
                : ReconciliationRun.Status.CLEAN;
        reconciliation.finishRun(runId, clock.instant(), verdict, results);
        return new Closed(verdict, results, findings.absoluteDrift());
    }

    /** Records a sweep that died without a verdict: FAILED, result columns stay NULL. */
    @Transactional
    public void failRun(UUID runId) {
        reconciliation.failRun(runId, clock.instant());
    }

    /**
     * What {@link #closeRun} recorded, for the listener that reports it: the verdict feeds the
     * {@code ledger.reconciliation.runs} outcome tag, the drift figures feed the PLAN §8
     * gauges.
     */
    public record Closed(ReconciliationRun.Status verdict, ReconciliationRun.Results results,
            long absoluteDrift) {

        public Closed {
            Objects.requireNonNull(verdict, "verdict");
            Objects.requireNonNull(results, "results");
            if (absoluteDrift < 0) {
                throw new IllegalArgumentException(
                        "absoluteDrift must be >= 0, got " + absoluteDrift);
            }
        }
    }
}
