package io.github.essandhu.ledger.adapter.reconciliation;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.batch.core.step.StepExecution;

import io.github.essandhu.ledger.application.service.ReconciliationRunService;

/**
 * The job's record-keeper and reporter: opens the RUNNING row before the sweep, and afterwards
 * either closes it with a verdict (delegating the decision to the core's
 * {@link ReconciliationRunService}) or stamps it FAILED. Metrics live here with the listener
 * that observes the outcomes (PLAN §8's placement rule — the application core stays
 * Micrometer-free): the {@code ledger.reconciliation.runs} counter tags the outcome, the
 * {@code .duration} timer records completed sweeps only — a FAILED run discards its sample,
 * the posting-duration discipline — and the drift gauges are overwritten per completed run.
 *
 * <p>In-flight samples are keyed by execution id (two concurrent sweeps are legal — they only
 * append their own findings), and callback failures are deliberately loud: an exception here
 * cannot un-fail the job, and a run row left RUNNING plus Batch's own logged metadata is a
 * truthful record of a listener that could not reach the database.
 */
class RunTrackingJobListener implements JobExecutionListener {

    static final String RUN_ID_PARAMETER = "runId";
    static final String TRIGGERED_BY_PARAMETER = "triggeredBy";

    private static final String RUNS = "ledger.reconciliation.runs";
    private static final String DURATION = "ledger.reconciliation.duration";

    private final ReconciliationRunService runService;
    private final DriftGauges gauges;
    private final MeterRegistry registry;
    private final Map<Long, Timer.Sample> inFlight = new ConcurrentHashMap<>();

    RunTrackingJobListener(ReconciliationRunService runService, DriftGauges gauges,
            MeterRegistry registry) {
        this.runService = runService;
        this.gauges = gauges;
        this.registry = registry;
    }

    @Override
    public void beforeJob(JobExecution jobExecution) {
        // Sample first: if openRun throws, afterJob still finds (and discards) the sample.
        inFlight.put(jobExecution.getId(), Timer.start(registry));
        runService.openRun(runId(jobExecution),
                jobExecution.getJobParameters().getString(TRIGGERED_BY_PARAMETER));
    }

    @Override
    public void afterJob(JobExecution jobExecution) {
        Timer.Sample sample = inFlight.remove(jobExecution.getId());
        if (jobExecution.getStatus() == BatchStatus.COMPLETED) {
            long accountsChecked = jobExecution.getStepExecutions().stream()
                    .mapToLong(StepExecution::getReadCount)
                    .sum();
            ReconciliationRunService.Closed closed =
                    runService.closeRun(runId(jobExecution), accountsChecked);
            gauges.record(closed.results().driftCount(), closed.absoluteDrift());
            registry.counter(RUNS, "outcome",
                    closed.verdict().name().toLowerCase(Locale.ROOT)).increment();
            sample.stop(registry.timer(DURATION));
        } else {
            runService.failRun(runId(jobExecution));
            registry.counter(RUNS, "outcome", "failed").increment();
            // Sample discarded: the duration series records completed sweeps only.
        }
    }

    private static UUID runId(JobExecution jobExecution) {
        return UUID.fromString(jobExecution.getJobParameters().getString(RUN_ID_PARAMETER));
    }
}
