package io.github.essandhu.ledger.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import io.github.essandhu.ledger.application.service.IdempotencyPurgeService;

/**
 * ADR-0004 retention: the purge scheduler, DESIGNED BUT SHIPPED DISABLED — this whole
 * configuration (scheduling included) exists only when {@code ledger.idempotency.purge.enabled}
 * is {@code true}, so v1's default is literally "no purge machinery in the context", and
 * enabling it is a configuration change, not a migration (the DELETE grant has existed since
 * V4). Records purge safely at any time: only replay/conflict diagnostics degrade — V3's
 * permanent backstop index keeps the double-post impossible forever (option 3b).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "ledger.idempotency.purge.enabled", havingValue = "true")
class IdempotencyPurgeConfig {

    @Bean
    IdempotencyPurger idempotencyPurger(IdempotencyPurgeService purge) {
        return new IdempotencyPurger(purge);
    }
}
