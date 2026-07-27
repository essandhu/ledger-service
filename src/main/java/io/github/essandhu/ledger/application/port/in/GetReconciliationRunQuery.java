package io.github.essandhu.ledger.application.port.in;

import java.util.UUID;

import io.github.essandhu.ledger.application.port.out.ReconciliationRun;

/**
 * GET /reconciliation-runs/{id} (PLAN §5, M6): one run's recorded state — RUNNING is
 * observable only from another principal's in-flight sweep (the trigger itself is
 * synchronous). Unknown id → {@link ReconciliationRunNotFound} (404).
 */
public interface GetReconciliationRunQuery {

    ReconciliationRun run(UUID runId);
}
