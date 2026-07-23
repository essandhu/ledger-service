package io.github.essandhu.ledger.config;

import java.time.Clock;

import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import io.github.essandhu.ledger.application.port.out.AccountRepository;
import io.github.essandhu.ledger.application.port.out.BalanceRepository;
import io.github.essandhu.ledger.application.port.out.IdGenerator;
import io.github.essandhu.ledger.application.port.out.JournalRepository;
import io.github.essandhu.ledger.application.service.AccountService;
import io.github.essandhu.ledger.application.service.BalanceService;
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

    @Bean
    PostingService postingService(AccountRepository accounts, JournalRepository journal,
            BalanceRepository balances, IdGenerator ids, Clock clock) {
        return new PostingService(accounts, journal, balances, ids, clock);
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
