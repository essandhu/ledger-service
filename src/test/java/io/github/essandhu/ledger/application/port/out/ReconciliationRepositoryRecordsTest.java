package io.github.essandhu.ledger.application.port.out;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * The repository's aggregate-carrying records: every field is produced by a SQL aggregate
 * (COUNT(*), Σ|delta|) that cannot go negative, so a negative here can only be an adapter
 * bug — unlike BalanceComparison's snapshot half, corruption is not on the menu.
 */
@DisplayName("ReconciliationRepository records: aggregate counts are non-negative")
class ReconciliationRepositoryRecordsTest {

    @Test
    @DisplayName("FindingAggregate accepts the clean run's (0, 0)")
    void finding_aggregate_accepts_zero() {
        ReconciliationRepository.FindingAggregate aggregate =
                new ReconciliationRepository.FindingAggregate(0, 0);
        assertThat(aggregate.driftCount()).isZero();
        assertThat(aggregate.absoluteDrift()).isZero();
    }

    @Test
    @DisplayName("FindingAggregate rejects a negative drift count or drift sum")
    void finding_aggregate_rejects_negatives() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ReconciliationRepository.FindingAggregate(-1, 0));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ReconciliationRepository.FindingAggregate(0, -1));
    }

    @Test
    @DisplayName("IntegrityCounts accepts the clean ledger's all-zero triple")
    void integrity_counts_accept_zero() {
        ReconciliationRepository.IntegrityCounts counts =
                new ReconciliationRepository.IntegrityCounts(0, 0, 0);
        assertThat(counts.currencyMismatchCount()).isZero();
        assertThat(counts.postedAtMismatchCount()).isZero();
        assertThat(counts.unbalancedCurrencyCount()).isZero();
    }

    @Test
    @DisplayName("IntegrityCounts rejects a negative in any of the three positions")
    void integrity_counts_reject_negatives() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ReconciliationRepository.IntegrityCounts(-1, 0, 0));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ReconciliationRepository.IntegrityCounts(0, -1, 0));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ReconciliationRepository.IntegrityCounts(0, 0, -1));
    }
}
