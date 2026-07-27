package io.github.essandhu.ledger.adapter.reconciliation;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.test.MetaDataInstanceFactory;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import io.github.essandhu.ledger.application.port.out.BalanceComparison;
import io.github.essandhu.ledger.application.port.out.ReconciliationFinding;
import io.github.essandhu.ledger.application.port.out.ReconciliationRun;
import io.github.essandhu.ledger.application.service.ReconciliationRunService;
import io.github.essandhu.ledger.domain.model.AccountId;
import io.github.essandhu.ledger.support.fakes.FakeReconciliationRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The listener's outcome mapping: completed runs close with a verdict, count under
 * their outcome tag, record a duration sample, and overwrite the gauges; FAILED runs stamp the
 * run row, count as {@code failed}, DISCARD the duration sample, and leave the gauges at the
 * last completed run's values.
 */
@DisplayName("RunTrackingJobListener: run records and metrics per job outcome")
class RunTrackingJobListenerTest {

    private static final Instant NOW = Instant.parse("2026-07-26T12:00:00Z");

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final FakeReconciliationRepository store = new FakeReconciliationRepository();
    private final ReconciliationRunService runService =
            new ReconciliationRunService(store, Clock.fixed(NOW, ZoneOffset.UTC));
    private final DriftGauges gauges = new DriftGauges(registry);
    private final RunTrackingJobListener listener =
            new RunTrackingJobListener(runService, gauges, registry);

    private JobExecution execution(UUID runId, long executionId) {
        return MetaDataInstanceFactory.createJobExecution(ReconciliationJobConfig.JOB_NAME, 1L,
                executionId, new JobParametersBuilder()
                        .addString(RunTrackingJobListener.RUN_ID_PARAMETER, runId.toString())
                        .addString(RunTrackingJobListener.TRIGGERED_BY_PARAMETER, "tester", false)
                        .toJobParameters());
    }

    @Test
    @DisplayName("a completed clean sweep: CLEAN row, outcome=clean count, duration sample, zeroed gauges")
    void completed_clean_sweep() {
        UUID runId = UUID.randomUUID();
        JobExecution execution = execution(runId, 1L);

        listener.beforeJob(execution);
        assertThat(store.runRecord(runId).status()).isEqualTo(ReconciliationRun.Status.RUNNING);
        assertThat(store.runRecord(runId).triggeredBy()).isEqualTo("tester");

        StepExecution step =
                MetaDataInstanceFactory.createStepExecution(execution, "reconcileAccountsStep", 1L);
        step.setReadCount(5);
        execution.addStepExecution(step); // Batch 6: the constructor no longer self-registers
        execution.setStatus(BatchStatus.COMPLETED);
        listener.afterJob(execution);

        ReconciliationRun run = store.runRecord(runId);
        assertThat(run.status()).isEqualTo(ReconciliationRun.Status.CLEAN);
        assertThat(run.results().orElseThrow().accountsChecked())
                .as("accountsChecked is the step's read count").isEqualTo(5);
        assertThat(counter("clean")).isEqualTo(1);
        assertThat(durationCount()).isEqualTo(1);
        assertThat(gauge("ledger.reconciliation.drift.accounts")).isZero();
        assertThat(gauge("ledger.reconciliation.drift.absolute")).isZero();
    }

    @Test
    @DisplayName("a completed drifted sweep: DRIFT row, outcome=drift count, gauges overwritten with the run's figures")
    void completed_drifted_sweep() {
        UUID runId = UUID.randomUUID();
        JobExecution execution = execution(runId, 2L);

        listener.beforeJob(execution);
        store.insertFindings(List.of(ReconciliationFinding.of(UUID.randomUUID(), runId,
                new BalanceComparison(new AccountId(UUID.randomUUID()), 107, 1, 100, 1))));
        StepExecution step =
                MetaDataInstanceFactory.createStepExecution(execution, "reconcileAccountsStep", 2L);
        step.setReadCount(3);
        execution.addStepExecution(step);
        execution.setStatus(BatchStatus.COMPLETED);
        listener.afterJob(execution);

        assertThat(store.runRecord(runId).status()).isEqualTo(ReconciliationRun.Status.DRIFT);
        assertThat(counter("drift")).isEqualTo(1);
        assertThat(gauge("ledger.reconciliation.drift.accounts")).isEqualTo(1);
        assertThat(gauge("ledger.reconciliation.drift.absolute")).isEqualTo(7);
    }

    @Test
    @DisplayName("a failed sweep: FAILED row, outcome=failed count, sample discarded, gauges untouched")
    void failed_sweep_leaves_gauges_and_discards_sample() {
        // First, a completed drifted sweep puts known values on the gauges.
        completed_drifted_sweep();
        long durationBefore = durationCount();

        UUID runId = UUID.randomUUID();
        JobExecution execution = execution(runId, 3L);
        listener.beforeJob(execution);
        execution.setStatus(BatchStatus.FAILED);
        listener.afterJob(execution);

        ReconciliationRun run = store.runRecord(runId);
        assertThat(run.status()).isEqualTo(ReconciliationRun.Status.FAILED);
        assertThat(run.results()).as("no partial counts on a failed run").isEmpty();
        assertThat(counter("failed")).isEqualTo(1);
        assertThat(durationCount())
                .as("the duration series records completed sweeps only")
                .isEqualTo(durationBefore);
        assertThat(gauge("ledger.reconciliation.drift.accounts"))
                .as("gauges keep the last COMPLETED run's truth").isEqualTo(1);
        assertThat(gauge("ledger.reconciliation.drift.absolute")).isEqualTo(7);
    }

    @Test
    @DisplayName("Batch swallows afterJob exceptions — so a verdict that cannot be recorded still counts as a failed run")
    void unrecordable_verdict_still_counts_failed() {
        // afterJob without beforeJob: no RUNNING row exists, so closeRun's finish update hits
        // the RUNNING-guard and throws — the transient-outage shape.
        UUID runId = UUID.randomUUID();
        JobExecution execution = execution(runId, 4L);
        StepExecution step =
                MetaDataInstanceFactory.createStepExecution(execution, "reconcileAccountsStep", 4L);
        step.setReadCount(1);
        execution.addStepExecution(step);
        execution.setStatus(BatchStatus.COMPLETED);

        double failedBefore = counter("failed");
        long durationBefore = durationCount();
        assertThatThrownBy(() -> listener.afterJob(execution))
                .isInstanceOf(IllegalStateException.class);
        assertThat(counter("failed"))
                .as("the alarm metric is emitted before the rethrow Batch will swallow")
                .isEqualTo(failedBefore + 1);
        assertThat(durationCount()).isEqualTo(durationBefore);
    }

    @Test
    @DisplayName("a FAILED job whose run row was never opened: the failed count is out before failRun's loud zero-row throw")
    void failed_count_survives_an_unopened_run() {
        UUID runId = UUID.randomUUID();
        JobExecution execution = execution(runId, 5L);
        execution.setStatus(BatchStatus.FAILED);

        double failedBefore = counter("failed");
        assertThatThrownBy(() -> listener.afterJob(execution))
                .isInstanceOf(IllegalStateException.class);
        assertThat(counter("failed")).isEqualTo(failedBefore + 1);
    }

    private double counter(String outcome) {
        Counter counter = registry.find("ledger.reconciliation.runs")
                .tags("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }

    private long durationCount() {
        Timer timer = registry.find("ledger.reconciliation.duration").timer();
        return timer == null ? 0 : timer.count();
    }

    private double gauge(String name) {
        return registry.get(name).gauge().value();
    }
}
