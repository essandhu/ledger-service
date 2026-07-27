package io.github.essandhu.ledger.adapter.reconciliation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;

/** The codebase's first gauges: registered once at construction, overwritten per run. */
@DisplayName("DriftGauges: eager registration, last-run overwrite semantics")
class DriftGaugesTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @Test
    @DisplayName("both gauges exist at 0 from construction — scrapeable before any run")
    void gauges_exist_at_zero_from_construction() {
        new DriftGauges(registry);
        assertThat(registry.get("ledger.reconciliation.drift.accounts").gauge().value()).isZero();
        assertThat(registry.get("ledger.reconciliation.drift.absolute").gauge().value()).isZero();
    }

    @Test
    @DisplayName("record() overwrites both values — last completed run wins, including back to zero")
    void record_overwrites_both_values() {
        DriftGauges gauges = new DriftGauges(registry);
        gauges.record(3, 42);
        assertThat(registry.get("ledger.reconciliation.drift.accounts").gauge().value())
                .isEqualTo(3);
        assertThat(registry.get("ledger.reconciliation.drift.absolute").gauge().value())
                .isEqualTo(42);
        gauges.record(0, 0);
        assertThat(registry.get("ledger.reconciliation.drift.accounts").gauge().value()).isZero();
        assertThat(registry.get("ledger.reconciliation.drift.absolute").gauge().value()).isZero();
    }
}
