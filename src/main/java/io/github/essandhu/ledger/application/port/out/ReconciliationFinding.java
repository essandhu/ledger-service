package io.github.essandhu.ledger.application.port.out;

import java.util.Objects;
import java.util.UUID;

import io.github.essandhu.ledger.domain.model.AccountId;

/**
 * One drifted account in one reconciliation sweep (I15): the snapshot pair, the recomputed
 * pair, and the balance delta — written once, never edited (the finding is the alertable fact;
 * ADR-0002 pins flag-and-investigate, never repair). The compact constructor mirrors V5's
 * CHECKs so an inconsistent finding cannot be constructed or loaded.
 */
public record ReconciliationFinding(
        UUID id,
        UUID runId,
        AccountId accountId,
        long snapshotBalance,
        long snapshotCount,
        long computedBalance,
        long computedCount,
        long delta) {

    public ReconciliationFinding {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(accountId, "accountId");
        // V5 reconciliation_finding_delta_consistent: the stored delta is derived, kept honest.
        if (delta != Math.subtractExact(snapshotBalance, computedBalance)) {
            throw new IllegalArgumentException("delta " + delta + " is not snapshotBalance - "
                    + "computedBalance = " + (snapshotBalance - computedBalance));
        }
        // V5 reconciliation_finding_records_drift: a finding exists iff drift exists.
        if (snapshotBalance == computedBalance && snapshotCount == computedCount) {
            throw new IllegalArgumentException(
                    "a finding must record actual drift, got identical pairs " + this);
        }
    }

    /** The one way findings come to exist: a drifted comparison, stamped with run and id. */
    public static ReconciliationFinding of(UUID id, UUID runId, BalanceComparison comparison) {
        return new ReconciliationFinding(id, runId, comparison.accountId(),
                comparison.snapshotBalance(), comparison.snapshotCount(),
                comparison.computedBalance(), comparison.computedCount(), comparison.delta());
    }
}
