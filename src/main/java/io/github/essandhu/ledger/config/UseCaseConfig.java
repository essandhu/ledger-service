package io.github.essandhu.ledger.config;

import java.time.Clock;
import java.time.Duration;

import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import io.github.essandhu.ledger.application.port.out.AccountRepository;
import io.github.essandhu.ledger.application.port.out.BalanceRepository;
import io.github.essandhu.ledger.application.port.out.IdGenerator;
import io.github.essandhu.ledger.application.port.out.IdempotencyRepository;
import io.github.essandhu.ledger.application.port.out.JournalRepository;
import io.github.essandhu.ledger.application.port.out.WriteResponseRenderer;
import io.github.essandhu.ledger.application.service.AccountService;
import io.github.essandhu.ledger.application.service.BalanceService;
import io.github.essandhu.ledger.application.service.IdempotencyPurgeService;
import io.github.essandhu.ledger.application.service.PostingService;

/**
 * Explicit wiring for the application core: services carry no stereotype annotations (the
 * ArchUnit I14 rules keep the core's Spring surface to @Transactional and @PreAuthorize), so
 * component scanning cannot find them — this config is where the hexagon's inside meets Spring.
 */
@Configuration(proxyBeanMethods = false)
class UseCaseConfig {

    @Bean
    AccountService accountService(AccountRepository accounts, BalanceRepository balances,
            IdGenerator ids, Clock clock) {
        return new AccountService(accounts, balances, ids, clock);
    }

    /**
     * M4: the idempotency TTL rides in as a plain {@link Duration} (ISO-8601 in config,
     * parsed here — the application core takes JDK types, not Spring conversion). 90 days is
     * ADR-0004's default; retention is indefinite in v1 regardless, because the purge ships
     * disabled ({@code IdempotencyPurgeConfig}).
     */
    @Bean
    PostingService postingService(AccountRepository accounts, JournalRepository journal,
            BalanceRepository balances, IdempotencyRepository idempotency,
            WriteResponseRenderer responses, IdGenerator ids, Clock clock,
            @Value("${ledger.idempotency.ttl:P90D}") String idempotencyTtl) {
        return new PostingService(accounts, journal, balances, idempotency, responses, ids,
                clock, Duration.parse(idempotencyTtl));
    }

    @Bean
    IdempotencyPurgeService idempotencyPurgeService(IdempotencyRepository idempotency,
            Clock clock) {
        return new IdempotencyPurgeService(idempotency, clock);
    }

    @Bean
    BalanceService balanceService(AccountRepository accounts, BalanceRepository balances,
            JournalRepository journal) {
        return new BalanceService(accounts, balances, journal);
    }

    /**
     * The three money-moving ports resolve to the metered decorator ({@code @Primary}), so
     * every caller gets PLAN §8's posting metrics for free; PostingService itself stays the
     * only GetJournalEntryQuery. Security and transactions live on the delegate's methods —
     * the decorator times from outside both, measuring what the caller experiences.
     */
    @Bean
    @Primary
    MeteredPostingUseCases meteredPostingUseCases(PostingService postingService,
            MeterRegistry registry) {
        return new MeteredPostingUseCases(postingService, registry);
    }
}
