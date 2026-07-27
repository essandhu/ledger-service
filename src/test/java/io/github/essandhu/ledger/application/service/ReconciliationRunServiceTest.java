package io.github.essandhu.ledger.application.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.essandhu.ledger.application.port.out.BalanceComparison;
import io.github.essandhu.ledger.application.port.out.ReconciliationFinding;
import io.github.essandhu.ledger.application.port.out.ReconciliationRepository;
import io.github.essandhu.ledger.application.port.out.ReconciliationRun;
import io.github.essandhu.ledger.domain.model.AccountId;
import io.github.essandhu.ledger.support.fakes.FakeReconciliationRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The run record-keeper's verdict logic (ADR-0002): what counts as drift is decided HERE, in
 * the core, Batch-free — the database CHECK that mirrors the decision stays a mirror.
 */
@DisplayName("ReconciliationRunService: open, close-with-verdict, fail")
class ReconciliationRunServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-26T12:00:00Z");
    private static final UUID RUN = UUID.fromString("00000000-0000-7000-8000-000000000042");

    private final FakeReconciliationRepository store = new FakeReconciliationRepository();
    private final ReconciliationRunService service =
            new ReconciliationRunService(store, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    @DisplayName("openRun records RUNNING with the injected Clock's instant and the caller")
    void open_run_records_running() {
        service.openRun(RUN, "alice");
        ReconciliationRun run = store.runRecord(RUN);
        assertThat(run.status()).isEqualTo(ReconciliationRun.Status.RUNNING);
        assertThat(run.startedAt()).isEqualTo(NOW);
        assertThat(run.triggeredBy()).isEqualTo("alice");
        assertThat(run.finishedAt()).isEmpty();
        assertThat(run.results()).isEmpty();
    }

    @Test
    @DisplayName("no findings + clean integrity checks close as CLEAN with zeroed counts")
    void clean_close() {
        service.openRun(RUN, "alice");
        ReconciliationRunService.Closed closed = service.closeRun(RUN, 12);

        assertThat(closed.verdict()).isEqualTo(ReconciliationRun.Status.CLEAN);
        assertThat(closed.results())
                .isEqualTo(new ReconciliationRun.Results(12, 0, 0, 0, 0));
        assertThat(closed.absoluteDrift()).isZero();
        ReconciliationRun run = store.runRecord(RUN);
        assertThat(run.status()).isEqualTo(ReconciliationRun.Status.CLEAN);
        assertThat(run.finishedAt()).contains(NOW);
    }

    @Test
    @DisplayName("findings close as DRIFT, with Σ|delta| aggregated for the gauges")
    void findings_close_as_drift() {
        service.openRun(RUN, "alice");
        store.insertFindings(List.of(
                ReconciliationFinding.of(UUID.randomUUID(), RUN, new BalanceComparison(
                        new AccountId(UUID.randomUUID()), 107, 1, 100, 1)),
                ReconciliationFinding.of(UUID.randomUUID(), RUN, new BalanceComparison(
                        new AccountId(UUID.randomUUID()), 97, 1, 100, 1))));

        ReconciliationRunService.Closed closed = service.closeRun(RUN, 12);

        assertThat(closed.verdict()).isEqualTo(ReconciliationRun.Status.DRIFT);
        assertThat(closed.results().driftCount()).isEqualTo(2);
        assertThat(closed.absoluteDrift()).as("|+7| + |−3|").isEqualTo(10);
        assertThat(store.runRecord(RUN).status()).isEqualTo(ReconciliationRun.Status.DRIFT);
    }

    @Test
    @DisplayName("integrity violations alone — no findings — still close as DRIFT")
    void integrity_violations_alone_are_drift() {
        service.openRun(RUN, "alice");
        store.seedIntegrityCounts(new ReconciliationRepository.IntegrityCounts(1, 0, 0));

        ReconciliationRunService.Closed closed = service.closeRun(RUN, 12);

        assertThat(closed.verdict()).isEqualTo(ReconciliationRun.Status.DRIFT);
        assertThat(closed.results().driftCount()).isZero();
        assertThat(closed.results().currencyMismatchCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Closed rejects a negative absoluteDrift — Σ|delta| is nonnegative by construction, so a negative figure is a caller bug")
    void closed_rejects_negative_absolute_drift() {
        ReconciliationRun.Results results = new ReconciliationRun.Results(12, 0, 0, 0, 0);
        assertThatThrownBy(() -> new ReconciliationRunService.Closed(
                ReconciliationRun.Status.CLEAN, results, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("absoluteDrift");
    }

    @Test
    @DisplayName("failRun stamps FAILED with no results — partial counts would be a lie")
    void fail_run_stamps_failed() {
        service.openRun(RUN, "alice");
        service.failRun(RUN);
        ReconciliationRun run = store.runRecord(RUN);
        assertThat(run.status()).isEqualTo(ReconciliationRun.Status.FAILED);
        assertThat(run.finishedAt()).contains(NOW);
        assertThat(run.results()).isEmpty();
    }

    @Test
    @DisplayName("a run of an empty ledger is CLEAN over zero accounts, not an error")
    void empty_ledger_closes_clean() {
        service.openRun(RUN, "alice");
        ReconciliationRunService.Closed closed = service.closeRun(RUN, 0);
        assertThat(closed.verdict()).isEqualTo(ReconciliationRun.Status.CLEAN);
        assertThat(closed.results().accountsChecked()).isZero();
    }
}
