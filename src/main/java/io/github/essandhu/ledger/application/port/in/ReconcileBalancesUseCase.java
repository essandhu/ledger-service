package io.github.essandhu.ledger.application.port.in;

import java.util.Objects;

import io.github.essandhu.ledger.application.port.out.ReconciliationRun;

/**
 * POST /reconciliation-runs (M6): run one reconciliation sweep NOW and return its
 * recorded verdict. Synchronous by design — the response carries the CLEAN/DRIFT answer, which
 * is what makes the admin trigger a demo and an operational tool rather than a fire-and-poll
 * ceremony; the deliberate cost (a large ledger holds the request open) is recorded in
 * ADR-0002's landing notes. No {@code Idempotency-Key}: this is not a money mover — every
 * trigger legitimately starts a fresh sweep, and retrying a timed-out one is harmless (the
 * account-management natural-retry posture).
 *
 * <p>The result is the port.out {@link ReconciliationRun} record itself: an operational record
 * with one canonical shape on both sides of the core — duplicating it into a view record would
 * be ceremony without a second reader.
 */
public interface ReconcileBalancesUseCase {

    ReconciliationRun trigger(TriggerReconciliationCommand command);

    record TriggerReconciliationCommand(String triggeredBy) {

        public TriggerReconciliationCommand {
            Objects.requireNonNull(triggeredBy, "triggeredBy");
        }
    }
}
