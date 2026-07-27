package io.github.essandhu.ledger.adapter.reconciliation;

import java.util.UUID;

import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import io.github.essandhu.ledger.application.port.out.BalanceComparison;
import io.github.essandhu.ledger.application.port.out.IdGenerator;
import io.github.essandhu.ledger.application.port.out.ReconciliationRepository;
import io.github.essandhu.ledger.application.service.ReconciliationRunService;

/**
 * The Spring Batch reconciliation job (ADR-0002, I15): one chunk-oriented step over every
 * account — port-backed keyset reader, pure drift-filter processor, port-backed findings
 * writer — bracketed by the run-tracking listener. Boot auto-configures the JobRepository and
 * JobOperator ({@code spring.batch.job.enabled=false} keeps the runner from firing this at
 * startup); this config contributes only the job itself.
 *
 * <p>Deliberately NOT restartable: every sweep is a fresh run with a fresh UUIDv7 identity
 * (the identifying {@code runId} parameter), because re-triggering is free and a resumed
 * half-old sweep would attribute one run's findings to two moments in time. For the same
 * reason there is no fault tolerance — a sweep that cannot finish becomes a FAILED run row
 * and an {@code outcome=failed} count, and the fix is to look, not to skip.
 */
@Configuration(proxyBeanMethods = false)
class ReconciliationJobConfig {

    static final String JOB_NAME = "reconciliationJob";

    /**
     * One page per chunk transaction: 500 accounts is small enough to keep each chunk's
     * transaction short next to the money path, large enough that a million-account sweep is
     * two thousand statements, not a million (the purge BATCH_SIZE reasoning).
     */
    static final int PAGE_SIZE = 500;

    @Bean
    Job reconciliationJob(JobRepository jobRepository, Step reconcileAccountsStep,
            RunTrackingJobListener runTrackingJobListener) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .listener(runTrackingJobListener)
                .preventRestart()
                .start(reconcileAccountsStep)
                .build();
    }

    @Bean
    Step reconcileAccountsStep(JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            ComparisonPageReader reconciliationComparisonReader,
            FindingsWriter reconciliationFindingsWriter) {
        return new StepBuilder("reconcileAccountsStep", jobRepository)
                .<BalanceComparison, BalanceComparison>chunk(PAGE_SIZE)
                // Load-bearing: Batch 6's chunk builder defaults to a RESOURCELESS transaction
                // manager — without the real one, findings would write outside any database
                // transaction.
                .transactionManager(transactionManager)
                .reader(reconciliationComparisonReader)
                // The verdict filter, pure and inline: clean accounts become nulls, which
                // Batch counts as filtered — so read/write/filter counts ARE checked/drifted/
                // clean, and the step's metadata tells the sweep's story by itself.
                .processor(comparison -> comparison.drifted() ? comparison : null)
                .writer(reconciliationFindingsWriter)
                .build();
    }

    @Bean
    @StepScope
    ComparisonPageReader reconciliationComparisonReader(ReconciliationRepository reconciliation) {
        return new ComparisonPageReader(reconciliation, PAGE_SIZE);
    }

    @Bean
    @StepScope
    FindingsWriter reconciliationFindingsWriter(ReconciliationRepository reconciliation,
            IdGenerator ids,
            @Value("#{jobParameters['runId']}") String runId) {
        return new FindingsWriter(reconciliation, ids, UUID.fromString(runId));
    }

    @Bean
    RunTrackingJobListener runTrackingJobListener(ReconciliationRunService runService,
            DriftGauges driftGauges, MeterRegistry registry) {
        return new RunTrackingJobListener(runService, driftGauges, registry);
    }

    @Bean
    DriftGauges driftGauges(MeterRegistry registry) {
        return new DriftGauges(registry);
    }
}
