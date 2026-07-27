package io.github.essandhu.ledger.adapter.reconciliation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.github.essandhu.ledger.application.port.out.IdGenerator;
import io.github.essandhu.ledger.application.port.out.ReconciliationTrigger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The scheduled driver: a fresh run id per sweep, attributed to "scheduler", straight to the
 * launcher (no {@code @PreAuthorize} gate — there is no principal). The disabled default is
 * proven against the full context ({@code ReconciliationJobIntegrationTest}); the enabled half
 * is proven here with the exact property, the purge-config precedent.
 */
@DisplayName("Reconciliation schedule: scheduler behavior and the enabled/disabled toggle")
class ReconciliationSchedulerTest {

    private record Started(UUID runId, String triggeredBy) {
    }

    private final List<Started> started = new ArrayList<>();
    private final ReconciliationTrigger recordingTrigger =
            (runId, triggeredBy) -> started.add(new Started(runId, triggeredBy));

    @Test
    @DisplayName("each sweep mints a fresh run id and attributes it to 'scheduler'")
    void each_sweep_is_a_fresh_scheduler_run() {
        ReconciliationScheduler scheduler =
                new ReconciliationScheduler(recordingTrigger, UUID::randomUUID);

        UUID first = scheduler.reconcile();
        UUID second = scheduler.reconcile();

        assertThat(started).hasSize(2);
        assertThat(started.get(0).runId()).isEqualTo(first);
        assertThat(started.get(1).runId()).isEqualTo(second);
        assertThat(first).isNotEqualTo(second);
        assertThat(started).allSatisfy(
                start -> assertThat(start.triggeredBy()).isEqualTo("scheduler"));
    }

    @Test
    @DisplayName("'enabling is a configuration change' is REAL: the exact property flips the scheduler bean into existence")
    void enabling_property_creates_the_scheduler() {
        // The IdempotencyPurgerTest precedent: prove @ConditionalOnProperty's property name and
        // the bean wiring, so a typo fails here instead of silently no-opping in production.
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withBean(ReconciliationTrigger.class, () -> recordingTrigger)
                .withBean(IdGenerator.class, () -> UUID::randomUUID)
                .withUserConfiguration(ReconciliationScheduleConfig.class);

        runner.withPropertyValues("ledger.reconciliation.schedule.enabled=true")
                .run(context -> assertThat(
                        context.getBeanNamesForType(ReconciliationScheduler.class)).hasSize(1));
        runner.run(context -> assertThat(
                context.getBeanNamesForType(ReconciliationScheduler.class))
                .as("no property, no schedule machinery")
                .isEmpty());
        runner.withPropertyValues("ledger.reconciliation.schedule.enabled=false")
                .run(context -> assertThat(
                        context.getBeanNamesForType(ReconciliationScheduler.class)).isEmpty());
    }
}
