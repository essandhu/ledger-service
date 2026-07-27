package io.github.essandhu.ledger.adapter.reconciliation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameter;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.test.MetaDataInstanceFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The trigger port's Batch face: one JobOperator start per sweep, handed the configured job
 * bean with the run id as the sole IDENTIFYING parameter — a fresh UUIDv7 per sweep means a
 * fresh JobInstance, so Batch's duplicate-instance refusals are unreachable by construction.
 * Should one arrive anyway, it is a programming error and the wrapper must say so (which run,
 * and why this cannot happen), not dress it up as a client outcome.
 */
@DisplayName("ReconciliationJobLauncher: identifying run id in, programming-error report out")
class ReconciliationJobLauncherTest {

    private record Launched(Job job, JobParameters parameters) {
    }

    // Pure payload: the launcher must hand this exact bean to the operator, and the stub only
    // records it — the job never executes in a unit test.
    private final Job reconciliationJob = execution -> {
    };

    @Test
    @DisplayName("a sweep starts the configured job with the run id identifying and triggeredBy as non-identifying audit")
    void run_id_is_the_only_identifying_parameter() {
        UUID runId = UUID.randomUUID();
        List<Launched> launches = new ArrayList<>();
        JobOperator recordingOperator = new StubJobOperator() {
            @Override
            public JobExecution start(Job job, JobParameters parameters) {
                launches.add(new Launched(job, parameters));
                return MetaDataInstanceFactory.createJobExecution();
            }
        };

        new ReconciliationJobLauncher(recordingOperator, reconciliationJob).start(runId, "admin");

        assertThat(launches).hasSize(1);
        assertThat(launches.get(0).job())
                .as("the launcher starts the configured job bean, not a by-name lookup")
                .isSameAs(reconciliationJob);
        JobParameters parameters = launches.get(0).parameters();
        JobParameter<?> runParameter =
                parameters.getParameter(RunTrackingJobListener.RUN_ID_PARAMETER);
        assertThat(runParameter.value()).isEqualTo(runId.toString());
        JobParameter<?> auditParameter =
                parameters.getParameter(RunTrackingJobListener.TRIGGERED_BY_PARAMETER);
        assertThat(auditParameter.value()).isEqualTo("admin");
        assertThat(parameters.getIdentifyingParameters())
                .as("identity is the run id ALONE — letting triggeredBy into the identity "
                        + "would fold the caller into JobInstance equality")
                .containsExactly(runParameter);
    }

    @Test
    @DisplayName("a Batch refusal surfaces as IllegalStateException naming the run and the unreachable-by-construction rationale")
    void batch_refusal_is_reported_as_a_programming_error() {
        UUID runId = UUID.randomUUID();
        // The representative refusal: the duplicate-instance objection that fresh run ids
        // exist to preclude. The launcher catches the JobExecutionException supertype, so one
        // subtype pins the whole declared family.
        JobInstanceAlreadyCompleteException refusal =
                new JobInstanceAlreadyCompleteException("instance already complete");
        ReconciliationJobLauncher launcher = new ReconciliationJobLauncher(new StubJobOperator() {
            @Override
            public JobExecution start(Job job, JobParameters parameters)
                    throws JobInstanceAlreadyCompleteException {
                throw refusal;
            }
        }, reconciliationJob);

        assertThatThrownBy(() -> launcher.start(runId, "admin"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(runId.toString())
                .hasMessageContaining(
                        "unreachable by construction (fresh UUIDv7 instance per sweep)")
                .hasCauseReference(refusal);
    }

    /**
     * JobOperator is the wide operations-console interface; the launcher's contract touches
     * exactly one method, {@code start(Job, JobParameters)} — inherited here as the interface
     * default, which throws UnsupportedOperationException until a test overrides it. Every
     * other method throws too, so an accidental second touch fails loudly instead of
     * vanishing into a silent no-op. (No Mockito in this repo — this is the honest cost of a
     * hand-rolled stub, paid once.)
     */
    private static class StubJobOperator implements JobOperator {

        private static UnsupportedOperationException outsideTheContract() {
            return new UnsupportedOperationException("outside the launcher's contract");
        }

        @Override
        public JobExecution run(Job job, JobParameters parameters) {
            throw outsideTheContract();
        }

        @Override
        public Set<String> getJobNames() {
            throw outsideTheContract();
        }

        @Override
        public Long restart(long executionId) {
            throw outsideTheContract();
        }

        @Override
        public JobExecution restart(JobExecution jobExecution) {
            throw outsideTheContract();
        }

        @Override
        public Long startNextInstance(String jobName) {
            throw outsideTheContract();
        }

        @Override
        public JobExecution startNextInstance(Job job) {
            throw outsideTheContract();
        }

        @Override
        public boolean stop(long executionId) {
            throw outsideTheContract();
        }

        @Override
        public boolean stop(JobExecution jobExecution) {
            throw outsideTheContract();
        }

        @Override
        public JobExecution abandon(long jobExecutionId) {
            throw outsideTheContract();
        }

        @Override
        public JobExecution abandon(JobExecution jobExecution) {
            throw outsideTheContract();
        }

        @Override
        public JobExecution recover(JobExecution jobExecution) {
            throw outsideTheContract();
        }

        @Override
        public List<Long> getExecutions(long instanceId) {
            throw outsideTheContract();
        }

        @Override
        public List<Long> getJobInstances(String jobName, int start, int count) {
            throw outsideTheContract();
        }

        @Override
        public Set<Long> getRunningExecutions(String jobName) {
            throw outsideTheContract();
        }

        @Override
        public String getParameters(long executionId) {
            throw outsideTheContract();
        }

        @Override
        public String getSummary(long executionId) {
            throw outsideTheContract();
        }

        @Override
        public Map<Long, String> getStepExecutionSummaries(long executionId) {
            throw outsideTheContract();
        }
    }
}
