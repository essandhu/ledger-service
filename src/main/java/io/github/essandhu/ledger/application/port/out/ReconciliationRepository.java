package io.github.essandhu.ledger.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.github.essandhu.ledger.application.port.in.PageSpec;
import io.github.essandhu.ledger.application.port.in.ReconciliationFindingsPage;

/**
 * Driven port for the reconciliation subsystem (ADR-0002, I15): the per-account comparison
 * scan, the run/finding records, and the run-level integrity checks. The adapter joins the
 * caller's transaction (it never opens its own) — chunk transactions belong to the Batch step,
 * record transactions to the application service.
 *
 * <p>Nothing here takes a lock: ADR-0003's single-lock-site rule stands ({@code
 * BalanceRepository.lockBalances} remains the only lock-taking API), and ADR-0002's argument
 * is that no lock is NEEDED — each comparison statement's READ COMMITTED snapshot is already
 * consistent with the atomic posting transactions it observes.
 */
public interface ReconciliationRepository {

    /**
     * One page of the comparison scan: every account_balance row with {@code account_id >
     * afterAccountId} (null = from the start) in the database's uuid order, each next to
     * {@code (SUM(amount), COUNT(*))} over its postings — ONE statement per page, which is the
     * whole consistency argument (ADR-0002). Keyset, not OFFSET, so a page boundary never
     * skips or repeats an account created mid-sweep; the keyset order is PostgreSQL's bytewise
     * uuid order, produced by the database itself (any Java-side mirror must derive from
     * {@link BalanceRepository#UUID_BYTEWISE_ORDER}). A short page ends the scan.
     */
    List<BalanceComparison> comparePage(UUID afterAccountId, int pageSize);

    /** Inserts the RUNNING run row — the job's opening act, before any comparison. */
    void insertRun(UUID runId, Instant startedAt, String triggeredBy);

    /**
     * The single set-based finish statement (the {@code applyDelta} precedent — never an
     * entity-managed read-modify-write): stamps {@code finishedAt}, the CLEAN/DRIFT verdict,
     * and the result counts onto the RUNNING row. Updating a row that is not RUNNING is a
     * programming error the adapter surfaces loudly.
     */
    void finishRun(UUID runId, Instant finishedAt, ReconciliationRun.Status verdict,
            ReconciliationRun.Results results);

    /** Marks a run that died without a verdict: status FAILED, result columns stay NULL. */
    void failRun(UUID runId, Instant finishedAt);

    Optional<ReconciliationRun> findRun(UUID runId);

    /** Appends one chunk's findings; write-once rows (V5 grants: no UPDATE, no DELETE). */
    void insertFindings(List<ReconciliationFinding> findings);

    /** {@code COUNT(*)} and {@code Σ|delta|} over one run's findings — the drift figures the
     * finish update and the PLAN §8 gauges report, aggregated where the rows live. */
    FindingAggregate aggregateFindings(UUID runId);

    record FindingAggregate(long driftCount, long absoluteDrift) {

        public FindingAggregate {
            if (driftCount < 0 || absoluteDrift < 0) {
                throw new IllegalArgumentException("aggregates must be >= 0, got " + this);
            }
        }
    }

    /**
     * The run-level integrity re-checks, each a single global statement: postings whose
     * denormalized {@code currency} disagrees with their account's, postings whose
     * denormalized {@code posted_at} disagrees with their entry's (PLAN §4.3), and currencies
     * whose ledger-wide {@code SUM(amount)} is nonzero (I5 at rest, ADR-0002's Proof section).
     */
    IntegrityCounts integrityCounts();

    record IntegrityCounts(
            long currencyMismatchCount,
            long postedAtMismatchCount,
            long unbalancedCurrencyCount) {

        public IntegrityCounts {
            if (currencyMismatchCount < 0 || postedAtMismatchCount < 0
                    || unbalancedCurrencyCount < 0) {
                throw new IllegalArgumentException("counts must be >= 0, got " + this);
            }
        }
    }

    /** One id-ordered page of a run's findings (UUIDv7 ids ⇒ id order is detection order). */
    ReconciliationFindingsPage findings(UUID runId, PageSpec page);
}
