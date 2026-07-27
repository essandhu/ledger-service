package io.github.essandhu.ledger.application.port.out;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * The run record's shape invariants — the Java mirror of V5's CHECKs, so a corrupted row fails
 * on load exactly like an ill-constructed record fails here.
 */
@DisplayName("ReconciliationRun: shape invariants mirror V5's CHECK constraints")
class ReconciliationRunTest {

    private static final UUID ID = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final Instant STARTED = Instant.parse("2026-07-26T10:00:00Z");
    private static final Instant FINISHED = Instant.parse("2026-07-26T10:00:03Z");
    private static final ReconciliationRun.Results CLEAN_RESULTS =
            new ReconciliationRun.Results(10, 0, 0, 0, 0);
    private static final ReconciliationRun.Results DRIFT_RESULTS =
            new ReconciliationRun.Results(10, 2, 0, 0, 0);

    @Test
    @DisplayName("the four legal shapes construct: RUNNING bare, CLEAN/DRIFT populated, FAILED bare")
    void legal_shapes_construct() {
        assertThatNoException().isThrownBy(() -> new ReconciliationRun(ID, STARTED,
                Optional.empty(), ReconciliationRun.Status.RUNNING, "t", Optional.empty()));
        assertThatNoException().isThrownBy(() -> new ReconciliationRun(ID, STARTED,
                Optional.of(FINISHED), ReconciliationRun.Status.CLEAN, "t",
                Optional.of(CLEAN_RESULTS)));
        assertThatNoException().isThrownBy(() -> new ReconciliationRun(ID, STARTED,
                Optional.of(FINISHED), ReconciliationRun.Status.DRIFT, "t",
                Optional.of(DRIFT_RESULTS)));
        assertThatNoException().isThrownBy(() -> new ReconciliationRun(ID, STARTED,
                Optional.of(FINISHED), ReconciliationRun.Status.FAILED, "t", Optional.empty()));
    }

    @Test
    @DisplayName("finished_shape: RUNNING with a finish instant (or finished without one) is rejected")
    void finished_shape_is_enforced() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ReconciliationRun(ID, STARTED,
                Optional.of(FINISHED), ReconciliationRun.Status.RUNNING, "t", Optional.empty()));
        assertThatIllegalArgumentException().isThrownBy(() -> new ReconciliationRun(ID, STARTED,
                Optional.empty(), ReconciliationRun.Status.CLEAN, "t",
                Optional.of(CLEAN_RESULTS)));
    }

    @Test
    @DisplayName("results_shape: a verdict without results (or results without a verdict) is rejected")
    void results_shape_is_enforced() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ReconciliationRun(ID, STARTED,
                Optional.of(FINISHED), ReconciliationRun.Status.DRIFT, "t", Optional.empty()));
        assertThatIllegalArgumentException().isThrownBy(() -> new ReconciliationRun(ID, STARTED,
                Optional.of(FINISHED), ReconciliationRun.Status.FAILED, "t",
                Optional.of(CLEAN_RESULTS)));
    }

    @Test
    @DisplayName("verdict_matches_counts: CLEAN with drift (or DRIFT without any) is rejected")
    void verdict_must_match_counts() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ReconciliationRun(ID, STARTED,
                Optional.of(FINISHED), ReconciliationRun.Status.CLEAN, "t",
                Optional.of(DRIFT_RESULTS)));
        assertThatIllegalArgumentException().isThrownBy(() -> new ReconciliationRun(ID, STARTED,
                Optional.of(FINISHED), ReconciliationRun.Status.DRIFT, "t",
                Optional.of(CLEAN_RESULTS)));
    }

    @Test
    @DisplayName("anyDrift: any nonzero count — findings, denormalizations, or global imbalance — is drift")
    void any_nonzero_count_is_drift() {
        assertThat(CLEAN_RESULTS.anyDrift()).isFalse();
        assertThat(new ReconciliationRun.Results(1, 0, 1, 0, 0).anyDrift()).isTrue();
        assertThat(new ReconciliationRun.Results(1, 0, 0, 1, 0).anyDrift()).isTrue();
        assertThat(new ReconciliationRun.Results(1, 0, 0, 0, 1).anyDrift()).isTrue();
    }

    @Test
    @DisplayName("result counts must be non-negative")
    void negative_counts_are_rejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ReconciliationRun.Results(-1, 0, 0, 0, 0));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ReconciliationRun.Results(0, -1, 0, 0, 0));
    }
}
