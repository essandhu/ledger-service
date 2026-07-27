package io.github.essandhu.ledger.adapter.reconciliation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import io.github.essandhu.ledger.application.port.out.IdGenerator;
import io.github.essandhu.ledger.application.port.out.ReconciliationTrigger;

/**
 * The reconciliation schedule, SHIPPED DISABLED — this whole configuration (scheduling
 * included) exists only when {@code ledger.reconciliation.schedule.enabled} is {@code true},
 * so the default context contains no scheduler machinery and enabling it is a configuration
 * change (the IdempotencyPurgeConfig pattern, its own {@code @EnableScheduling} because
 * conditional configs cannot borrow each other's). The admin trigger endpoint works either
 * way; the schedule is for running reconciliation as the standing safety net ADR-0002
 * describes rather than an on-demand check.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "ledger.reconciliation.schedule.enabled", havingValue = "true")
class ReconciliationScheduleConfig {

    @Bean
    ReconciliationScheduler reconciliationScheduler(ReconciliationTrigger trigger,
            IdGenerator ids) {
        return new ReconciliationScheduler(trigger, ids);
    }
}
