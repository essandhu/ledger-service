package io.github.essandhu.ledger.adapter.web;

import java.util.UUID;

import io.github.essandhu.ledger.application.port.out.ReconciliationFinding;

/** One drifted account on the wire (M6): both pairs and the delta, verbatim raw
 * signed minor units — the statement-line posture, no natural-sign interpretation here. */
record ReconciliationFindingResponse(
        UUID id,
        UUID accountId,
        long snapshotBalance,
        long snapshotCount,
        long computedBalance,
        long computedCount,
        long delta) {

    static ReconciliationFindingResponse from(ReconciliationFinding finding) {
        return new ReconciliationFindingResponse(finding.id(), finding.accountId().value(),
                finding.snapshotBalance(), finding.snapshotCount(), finding.computedBalance(),
                finding.computedCount(), finding.delta());
    }
}
