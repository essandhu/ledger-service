package io.github.essandhu.ledger.application.port.out;

import java.util.Objects;

import io.github.essandhu.ledger.domain.model.AccountId;

/**
 * One account's snapshot pair next to the pair recomputed from its postings — both halves read
 * by ONE SQL statement (ADR-0002: a single statement's READ COMMITTED snapshot is consistent
 * with the atomic posting transactions it observes, so this comparison cannot false-positive
 * against live traffic). The pair is the point: compensating corruption can leave SUM(amount)
 * intact while COUNT(*) diverges, which is exactly what the posting_count watermark catches.
 */
public record BalanceComparison(
        AccountId accountId,
        long snapshotBalance,
        long snapshotCount,
        long computedBalance,
        long computedCount) {

    public BalanceComparison {
        Objects.requireNonNull(accountId, "accountId");
        if (computedCount < 0) {
            throw new IllegalArgumentException("computedCount must be >= 0, got " + computedCount);
        }
        // snapshotCount is NOT range-checked here: a negative watermark would be corruption,
        // and corruption is this type's subject matter, not its constructor's veto.
    }

    /** True when either half of the pair disagrees — this account gets a finding. */
    public boolean drifted() {
        return snapshotBalance != computedBalance || snapshotCount != computedCount;
    }

    /**
     * Snapshot minus computed: positive = the snapshot claims money the postings cannot back.
     * Exact arithmetic — two in-range balances can differ by more than a long holds, and an
     * overflowing comparison must fail the run loudly (FAILED), never wrap into a small lie.
     */
    public long delta() {
        return Math.subtractExact(snapshotBalance, computedBalance);
    }
}
