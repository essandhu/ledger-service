package io.github.essandhu.ledger.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.essandhu.ledger.application.port.out.AccountRepository;
import io.github.essandhu.ledger.application.port.out.IdGenerator;
import io.github.essandhu.ledger.application.service.AccountService;

/**
 * Explicit wiring for the application core: services carry no stereotype annotations (the
 * ArchUnit I14 rules keep the core's Spring surface to @Transactional and @PreAuthorize), so
 * component scanning cannot find them — this config is where the hexagon's inside meets Spring.
 */
@Configuration(proxyBeanMethods = false)
class UseCaseConfig {

    @Bean
    AccountService accountService(AccountRepository accounts, IdGenerator ids, Clock clock) {
        return new AccountService(accounts, ids, clock);
    }
}
