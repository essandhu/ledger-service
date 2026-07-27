package io.github.essandhu.ledger.application.port.out;

import java.util.UUID;

/**
 * Driven port that runs one reconciliation sweep, synchronously: when {@code start} returns
 * normally, the sweep is over and — in every path where the database could record it — the
 * run's {@code reconciliation_run} row is terminal (written by the job's own listeners; the
 * caller reads the verdict back through {@link ReconciliationRepository}). The one honest
 * exception: if the terminal record write ITSELF fails, the row stays RUNNING, the failure is
 * counted under {@code ledger.reconciliation.runs{outcome=failed}} and logged — a RUNNING row
 * whose Batch execution has ended is exactly what that incident looks like. This interface is
 * what keeps Spring Batch out of the application core (the service package holds no web, JPA,
 * or batch types — I14): the adapter behind it owns the JobOperator, the chunking, and the
 * metrics.
 */
public interface ReconciliationTrigger {

    /**
     * Runs the sweep identified by {@code runId} (a fresh UUIDv7 per call — every trigger is a
     * new job instance, so Batch's duplicate-instance machinery never has cause to object) on
     * behalf of {@code triggeredBy} (JWT subject, or "scheduler"). Blocks until the run is
     * terminal; infrastructure failures surface as unchecked exceptions (500-shaped — there is
     * no business rejection in "please reconcile").
     */
    void start(UUID runId, String triggeredBy);
}
