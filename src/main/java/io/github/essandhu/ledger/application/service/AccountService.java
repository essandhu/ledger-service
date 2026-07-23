package io.github.essandhu.ledger.application.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

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
import io.github.essandhu.ledger.application.port.out.BalanceRepository;
import io.github.essandhu.ledger.application.port.out.IdGenerator;
import io.github.essandhu.ledger.domain.error.AmountOverflow;
import io.github.essandhu.ledger.domain.model.Account;
import io.github.essandhu.ledger.domain.model.AccountBalance;
import io.github.essandhu.ledger.domain.model.AccountId;
import io.github.essandhu.ledger.domain.model.AccountStatus;
import io.github.essandhu.ledger.domain.model.CloseBalanceRule;

/**
 * Account use cases. Registered as a bean by config wiring, not component scanning — the
 * application layer carries no stereotype annotations; declarative {@code @Transactional} and
 * {@code @PreAuthorize} (the method-security half of I13's two layers) are its only Spring
 * dependencies, as enforced by ArchUnit (I14).
 *
 * <p>Since M2 the lifecycle meets the posting engine: create seeds the zero balance snapshot
 * in the same transaction (the V2 forward-contract), and status transitions acquire the SAME
 * account_balance lock the posting path holds (ADR-0003) — a freeze or close decided against
 * a status a posting already read would be a race; on the shared lock it is a queue.
 */
public class AccountService
        implements CreateAccountUseCase, UpdateAccountUseCase, GetAccountQuery, ListAccountsQuery {

    private final AccountRepository accounts;
    private final BalanceRepository balances;
    private final IdGenerator ids;
    private final Clock clock;

    public AccountService(AccountRepository accounts, BalanceRepository balances,
            IdGenerator ids, Clock clock) {
        this.accounts = accounts;
        this.balances = balances;
        this.ids = ids;
        this.clock = clock;
    }

    @Override
    @PreAuthorize("hasRole('LEDGER_ADMIN')")
    @Transactional
    public Account create(CreateAccountCommand command) {
        Instant now = clock.instant();
        Account account = Account.open(new AccountId(ids.nextId()), command.name(),
                command.currency(), command.type(), command.allowNegative(), now);
        accounts.insert(account);
        // V2 forward-contract, discharged: every account carries its zero snapshot row from
        // birth, in the same transaction — the posting engine locks the balance row of every
        // touched account (ADR-0003), so an account without one would break the lock protocol.
        balances.insertZero(account.id(), now);
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
            // A pure rename takes NO balance lock: renames are metadata, and concurrent
            // metadata edits are the version guard's race to decide (ADR-0003 — the balance
            // lock exists to serialize posting against lifecycle STATUS, not against names).
            updated = updated.rename(command.newName().get(), now);
        }
        if (command.newStatus().isPresent()) {
            updated = applyStatus(updated, command.newStatus().get(), now);
        }
        // Domain no-ops return the same instance; identity tells us whether anything changed,
        // so a declarative no-op PATCH writes nothing (no version bump, no updated_at drift).
        if (updated != current) {
            accounts.update(updated);
        }
        return updated;
    }

    /**
     * Lifecycle transitions take the account_balance lock BEFORE the transition check
     * (ADR-0003: lifecycle and posting serialize on the same lock, so no posting that read
     * the old status can still be in flight when the status flips — the close-vs-post race
     * dies here). Under that lock the CLOSED branch evaluates I12's posting half,
     * {@link CloseBalanceRule}: close only at natural balance zero.
     *
     * <p>The same-state no-op returns BEFORE locking — a deliberate decision, documented as
     * such: M1's declarative-PATCH contract is that asserting the current state persists
     * nothing, and the no-op verdict depends only on the status value, which no posting
     * mutates — so taking the lock would serialize innocent retries behind the posting path
     * while protecting nothing.
     */
    private Account applyStatus(Account account, AccountStatus target, Instant now) {
        if (target == account.status()) {
            return account; // declarative no-op — same contract as Account.transitionTo
        }
        List<AccountBalance> locked = balances.lockBalances(List.of(account.id()));
        if (locked.isEmpty()) {
            // Unreachable by construction (V3 backfill + create-tx insert): absence is
            // corruption, not a client error — fail loudly.
            throw new IllegalStateException(
                    "account %s has no balance row (V2 forward-contract violated)"
                            .formatted(account.id().value()));
        }
        Account transitioned = account.transitionTo(target, now);
        if (target == AccountStatus.CLOSED) {
            try {
                CloseBalanceRule.ensureZeroForClose(locked.get(0), account.type());
            } catch (ArithmeticException overflow) {
                // Raw Long.MIN_VALUE on a credit-normal account (reachable: allow-negative
                // skips the overdraft floor) has no 64-bit natural — the checked multiply in
                // AccountBalance.natural refuses, and this accumulation point translates the
                // refusal into the 422 rejection (ADR-0001), the same discipline as posting.
                throw new AmountOverflow(
                        "account %s natural balance has no 64-bit representation (ADR-0001: checked arithmetic rejects, never wraps)"
                                .formatted(account.id().value()));
            }
        }
        return transitioned;
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
