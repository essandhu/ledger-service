package io.github.essandhu.ledger.adapter.reconciliation;

import java.util.UUID;

import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecutionException;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.stereotype.Component;

import io.github.essandhu.ledger.application.port.out.ReconciliationTrigger;

/**
 * The {@link ReconciliationTrigger} port over Spring Batch's JobOperator — the one class that
 * turns "run a sweep" into a job launch, shared by the admin use case and the scheduler. The
 * launch is synchronous (Boot's default operator runs the job in the calling thread), which is
 * exactly the port's contract: when this returns, the run row is terminal.
 *
 * <p>{@code runId} is the IDENTIFYING parameter: a fresh UUIDv7 per sweep means a fresh
 * JobInstance per sweep, so Batch's duplicate-instance exceptions are unreachable by
 * construction — reaching one anyway is a programming error, reported as such, not a client
 * outcome. {@code triggeredBy} rides along non-identifying: audit data in
 * BATCH_JOB_EXECUTION_PARAMS (and the run row), not identity.
 */
@Component
class ReconciliationJobLauncher implements ReconciliationTrigger {

    private final JobOperator jobOperator;
    private final Job reconciliationJob;

    ReconciliationJobLauncher(JobOperator jobOperator, Job reconciliationJob) {
        this.jobOperator = jobOperator;
        this.reconciliationJob = reconciliationJob;
    }

    @Override
    public void start(UUID runId, String triggeredBy) {
        JobParameters parameters = new JobParametersBuilder()
                .addString(RunTrackingJobListener.RUN_ID_PARAMETER, runId.toString())
                .addString(RunTrackingJobListener.TRIGGERED_BY_PARAMETER, triggeredBy, false)
                .toJobParameters();
        try {
            jobOperator.start(reconciliationJob, parameters);
        } catch (JobExecutionException e) {
            throw new IllegalStateException(
                    "reconciliation launch refused for run " + runId
                            + " — unreachable by construction (fresh UUIDv7 instance per sweep)",
                    e);
        }
    }
}
