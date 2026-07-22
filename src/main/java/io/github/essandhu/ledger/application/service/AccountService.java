package io.github.essandhu.ledger.application.service;

import java.time.Clock;
import java.time.Instant;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

import io.github.essandhu.ledger.application.port.in.AccountFilter;
import io.github.essandhu.ledger.application.port.in.AccountNotFound;
import io.github.essandhu.ledger.application.port.in.AccountPage;
import io.github.essandhu.ledger.application.port.in.CreateAccountUseCase;
import io.github.essandhu.ledger.application.port.in.GetAccountQuery;
import io.github.essandhu.ledger.application.port.in.ListAccountsQuery;
import io.github.essandhu.ledger.application.port.in.PageSpec;
import io.github.essandhu.ledger.application.port.in.UpdateAccountUseCase;
import io.github.essandhu.ledger.application.port.out.AccountRepository;
import io.github.essandhu.ledger.application.port.out.IdGenerator;
import io.github.essandhu.ledger.domain.model.Account;
import io.github.essandhu.ledger.domain.model.AccountId;

/**
 * Account use cases. Registered as a bean by config wiring, not component scanning — the
 * application layer carries no stereotype annotations; declarative {@code @Transactional} and
 * {@code @PreAuthorize} (the method-security half of I13's two layers) are its only Spring
 * dependencies, as enforced by ArchUnit (I14).
 */
public class AccountService
        implements CreateAccountUseCase, UpdateAccountUseCase, GetAccountQuery, ListAccountsQuery {

    private final AccountRepository accounts;
    private final IdGenerator ids;
    private final Clock clock;

    public AccountService(AccountRepository accounts, IdGenerator ids, Clock clock) {
        this.accounts = accounts;
        this.ids = ids;
        this.clock = clock;
    }

    @Override
    @PreAuthorize("hasRole('LEDGER_ADMIN')")
    @Transactional
    public Account create(CreateAccountCommand command) {
        Account account = Account.open(new AccountId(ids.nextId()), command.name(),
                command.currency(), command.type(), command.allowNegative(), clock.instant());
        accounts.insert(account);
        return account;
    }

    @Override
    @PreAuthorize("hasRole('LEDGER_ADMIN')")
    @Transactional
    public Account update(UpdateAccountCommand command) {
        Account current = accounts.findById(command.id())
                .orElseThrow(() -> new AccountNotFound(command.id()));
        Instant now = clock.instant();
        Account updated = current;
        if (command.newName().isPresent()) {
            updated = updated.rename(command.newName().get(), now);
        }
        if (command.newStatus().isPresent()) {
            updated = updated.transitionTo(command.newStatus().get(), now);
        }
        // Domain no-ops return the same instance; identity tells us whether anything changed,
        // so a declarative no-op PATCH writes nothing (no version bump, no updated_at drift).
        if (updated != current) {
            accounts.update(updated);
        }
        return updated;
    }

    @Override
    @PreAuthorize("hasRole('LEDGER_READ')")
    @Transactional(readOnly = true)
    public Account byId(AccountId id) {
        return accounts.findById(id).orElseThrow(() -> new AccountNotFound(id));
    }

    @Override
    @PreAuthorize("hasRole('LEDGER_READ')")
    @Transactional(readOnly = true)
    public AccountPage list(AccountFilter filter, PageSpec page) {
        return accounts.findAll(filter, page);
    }
}
