package io.github.essandhu.ledger.application.port.out;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.essandhu.ledger.domain.model.AccountId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The pair comparison ADR-0002 pins: either half disagreeing is drift. */
@DisplayName("BalanceComparison: the (balance, posting_count) pair comparison")
class BalanceComparisonTest {

    private static final AccountId ACCOUNT = new AccountId(UUID.randomUUID());

    @Test
    @DisplayName("agreement on both halves is clean")
    void identical_pairs_are_clean() {
        BalanceComparison comparison = new BalanceComparison(ACCOUNT, 100, 3, 100, 3);
        assertThat(comparison.drifted()).isFalse();
        assertThat(comparison.delta()).isZero();
    }

    @Test
    @DisplayName("a balance mismatch is drift, and delta is snapshot − computed")
    void balance_mismatch_is_drift() {
        BalanceComparison comparison = new BalanceComparison(ACCOUNT, 107, 3, 100, 3);
        assertThat(comparison.drifted()).isTrue();
        assertThat(comparison.delta()).isEqualTo(7);
    }

    @Test
    @DisplayName("a count-only mismatch is drift with delta 0 — the watermark's whole point")
    void count_only_mismatch_is_drift() {
        BalanceComparison comparison = new BalanceComparison(ACCOUNT, 100, 4, 100, 3);
        assertThat(comparison.drifted()).isTrue();
        assertThat(comparison.delta()).isZero();
    }

    @Test
    @DisplayName("a delta that overflows long fails loudly instead of wrapping into a small lie")
    void overflowing_delta_fails_loudly() {
        BalanceComparison comparison =
                new BalanceComparison(ACCOUNT, Long.MAX_VALUE, 1, -2, 1);
        assertThat(comparison.drifted()).isTrue();
        assertThatThrownBy(comparison::delta).isInstanceOf(ArithmeticException.class);
    }

    @Test
    @DisplayName("a negative snapshot watermark is representable — corruption is subject matter, not a constructor veto")
    void negative_snapshot_count_is_representable() {
        BalanceComparison comparison = new BalanceComparison(ACCOUNT, 0, -1, 0, 0);
        assertThat(comparison.drifted()).isTrue();
    }
}
