package io.github.essandhu.ledger.application.port.out;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.essandhu.ledger.domain.model.AccountId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/** The finding record's V5-mirroring invariants: honest delta, actual drift. */
@DisplayName("ReconciliationFinding: delta consistency and the records-drift rule")
class ReconciliationFindingTest {

    private static final UUID ID = UUID.randomUUID();
    private static final UUID RUN = UUID.randomUUID();
    private static final AccountId ACCOUNT = new AccountId(UUID.randomUUID());

    @Test
    @DisplayName("of() stamps a drifted comparison with id and run, delta derived")
    void of_maps_a_drifted_comparison() {
        ReconciliationFinding finding = ReconciliationFinding.of(ID, RUN,
                new BalanceComparison(ACCOUNT, 107, 1, 100, 1));
        assertThat(finding.id()).isEqualTo(ID);
        assertThat(finding.runId()).isEqualTo(RUN);
        assertThat(finding.accountId()).isEqualTo(ACCOUNT);
        assertThat(finding.delta()).isEqualTo(7);
    }

    @Test
    @DisplayName("a dishonest stored delta is rejected")
    void inconsistent_delta_is_rejected() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> new ReconciliationFinding(ID, RUN, ACCOUNT, 107, 1, 100, 1, 5));
    }

    @Test
    @DisplayName("identical pairs are not a finding — a finding exists iff drift exists")
    void identical_pairs_are_rejected() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> new ReconciliationFinding(ID, RUN, ACCOUNT, 100, 1, 100, 1, 0));
    }

    @Test
    @DisplayName("count-only drift is a legal finding with delta 0")
    void count_only_drift_constructs() {
        ReconciliationFinding finding =
                new ReconciliationFinding(ID, RUN, ACCOUNT, 100, 2, 100, 1, 0);
        assertThat(finding.delta()).isZero();
    }
}
