package io.github.essandhu.ledger.application.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.essandhu.ledger.application.port.in.AccountFilter;
import io.github.essandhu.ledger.application.port.in.AccountNotFound;
import io.github.essandhu.ledger.application.port.in.AccountPage;
import io.github.essandhu.ledger.application.port.in.CreateAccountUseCase.CreateAccountCommand;
import io.github.essandhu.ledger.application.port.in.PageSpec;
import io.github.essandhu.ledger.application.port.in.UpdateAccountUseCase.UpdateAccountCommand;
import io.github.essandhu.ledger.domain.error.AccountBalanceNotZero;
import io.github.essandhu.ledger.domain.error.AccountClosed;
import io.github.essandhu.ledger.domain.error.AmountOverflow;
import io.github.essandhu.ledger.domain.error.InvalidAccountInput;
import io.github.essandhu.ledger.domain.error.InvalidStatusTransition;
import io.github.essandhu.ledger.domain.model.Account;
import io.github.essandhu.ledger.domain.model.AccountBalance;
import io.github.essandhu.ledger.domain.model.AccountId;
import io.github.essandhu.ledger.domain.model.AccountStatus;
import io.github.essandhu.ledger.domain.model.AccountType;
import io.github.essandhu.ledger.domain.model.CurrencyCode;
import io.github.essandhu.ledger.support.fakes.FakeAccountRepository;
import io.github.essandhu.ledger.support.fakes.FakeBalanceRepository;
import io.github.essandhu.ledger.support.fakes.FixedIdGenerator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Use-case orchestration over fakes: id from the generator port, time from the injected Clock,
 * atomic PATCH semantics (rename and status in one operation), and no-op detection — a PATCH
 * that changes nothing must not write (no version bump, no updated_at drift). Since M2 the
 * lifecycle half meets the posting half: create seeds the zero balance snapshot (V2
 * forward-contract), status transitions take the account_balance lock BEFORE deciding
 * (ADR-0003), and close evaluates the zero-natural-balance rule under that lock (I12).
 */
@DisplayName("AccountService: use-case orchestration")
class AccountServiceTest {

    private static final Instant T0 = Instant.parse("2026-07-22T10:00:00Z");
    private static final Instant T1 = T0.plusSeconds(60);
    private static final UUID ID = UUID.fromString("019817b4-0000-7000-8000-000000000001");

    private final FakeAccountRepository repository = new FakeAccountRepository();
    private final FakeBalanceRepository balances = new FakeBalanceRepository();

    private AccountService serviceAt(Instant now) {
        return new AccountService(repository, balances, new FixedIdGenerator(ID),
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private Account seededActive() {
        Account account = Account.open(new AccountId(ID), "Operating cash", new CurrencyCode("EUR"),
                AccountType.ASSET, false, T0);
        repository.seed(account);
        // Every real account has its snapshot row by construction (V3 backfill + create-tx
        // insert) — the fixture honors the same invariant, at zero.
        balances.seed(new AccountBalance(account.id(), 0, 0, T0));
        return account;
    }

    @Test
    @DisplayName("create: id from the IdGenerator port, timestamps from the Clock, persisted")
    void create_uses_generator_and_clock() {
        Account created = serviceAt(T0).create(
                new CreateAccountCommand("Operating cash", new CurrencyCode("EUR"), AccountType.ASSET, false));
        assertThat(created.id()).isEqualTo(new AccountId(ID));
        assertThat(created.status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(created.createdAt()).isEqualTo(T0);
        assertThat(created.updatedAt()).isEqualTo(T0);
        assertThat(repository.findById(new AccountId(ID))).contains(created);
    }

    @Test
    @DisplayName("create: domain validation propagates (defense in depth below web validation)")
    void create_propagates_domain_validation() {
        AccountService service = serviceAt(T0);
        CreateAccountCommand blankName =
                new CreateAccountCommand("  ", new CurrencyCode("EUR"), AccountType.ASSET, false);
        assertThatThrownBy(() -> service.create(blankName)).isInstanceOf(InvalidAccountInput.class);
    }

    @Test
    @DisplayName("update: rename and close in ONE command apply atomically, in that order")
    void update_applies_rename_then_status() {
        seededActive();
        Account updated = serviceAt(T1).update(new UpdateAccountCommand(
                new AccountId(ID), Optional.of("Archived cash"), Optional.of(AccountStatus.CLOSED)));
        assertThat(updated.name()).isEqualTo("Archived cash");
        assertThat(updated.status()).isEqualTo(AccountStatus.CLOSED);
        assertThat(updated.updatedAt()).isEqualTo(T1);
        assertThat(repository.findById(new AccountId(ID))).contains(updated);
    }

    @Test
    @DisplayName("update: an illegal transition writes nothing — atomicity of the combined PATCH")
    void update_with_illegal_transition_writes_nothing() {
        Account closed = seededActive().transitionTo(AccountStatus.CLOSED, T0);
        repository.seed(closed);
        AccountService service = serviceAt(T1);

        // Status-only: the illegal edge itself.
        UpdateAccountCommand thaw = new UpdateAccountCommand(
                new AccountId(ID), Optional.empty(), Optional.of(AccountStatus.ACTIVE));
        assertThatThrownBy(() -> service.update(thaw))
                .isInstanceOf(InvalidStatusTransition.class);

        // Combined: rename is applied first, so the closed-account guard fires first —
        // a different 422, same atomicity (nothing may be written either way).
        UpdateAccountCommand renameAndThaw = new UpdateAccountCommand(
                new AccountId(ID), Optional.of("New name"), Optional.of(AccountStatus.ACTIVE));
        assertThatThrownBy(() -> service.update(renameAndThaw))
                .isInstanceOf(AccountClosed.class);

        assertThat(repository.updateCalls()).isZero();
        assertThat(repository.findById(new AccountId(ID))).contains(closed);
    }

    @Test
    @DisplayName("update: a command that changes nothing performs no write at all")
    void noop_update_does_not_write() {
        Account account = seededActive();
        Account result = serviceAt(T1).update(new UpdateAccountCommand(
                new AccountId(ID), Optional.of("Operating cash"), Optional.of(AccountStatus.ACTIVE)));
        assertThat(result).isEqualTo(account);
        assertThat(result.updatedAt()).isEqualTo(T0);
        assertThat(repository.updateCalls()).isZero();
    }

    @Test
    @DisplayName("update: an empty command is a no-op returning the current state")
    void empty_update_is_noop() {
        Account account = seededActive();
        Account result = serviceAt(T1).update(
                new UpdateAccountCommand(new AccountId(ID), Optional.empty(), Optional.empty()));
        assertThat(result).isEqualTo(account);
        assertThat(repository.updateCalls()).isZero();
    }

    @Test
    @DisplayName("update/get: unknown id raises AccountNotFound")
    void unknown_id_raises_not_found() {
        AccountService service = serviceAt(T0);
        AccountId unknown = new AccountId(UUID.fromString("019817b4-0000-7000-8000-00000000ffff"));
        assertThatThrownBy(() -> service.update(
                new UpdateAccountCommand(unknown, Optional.of("x"), Optional.empty())))
                .isInstanceOf(AccountNotFound.class);
        assertThatThrownBy(() -> service.byId(unknown)).isInstanceOf(AccountNotFound.class);
    }

    @Test
    @DisplayName("list: filters delegate to the repository port, id-ordered")
    void list_delegates_filtering() {
        AccountService service = serviceAt(T0);
        UUID id2 = UUID.fromString("019817b4-0000-7000-8000-000000000002");
        AccountService seeded = new AccountService(repository, balances,
                new FixedIdGenerator(ID, id2), Clock.fixed(T0, ZoneOffset.UTC));
        seeded.create(new CreateAccountCommand("A", new CurrencyCode("EUR"), AccountType.ASSET, false));
        seeded.create(new CreateAccountCommand("B", new CurrencyCode("EUR"), AccountType.EQUITY, true));

        AccountPage all = service.list(AccountFilter.none(), new PageSpec(0, 20));
        assertThat(all.content()).extracting(Account::name).containsExactly("A", "B");
        assertThat(all.totalElements()).isEqualTo(2);

        AccountPage equity = service.list(
                new AccountFilter(Optional.of(AccountType.EQUITY), Optional.empty()), new PageSpec(0, 20));
        assertThat(equity.content()).extracting(Account::name).containsExactly("B");
    }

    @Test
    @DisplayName("create: seeds the zero balance snapshot in the same operation (V2 forward-contract)")
    void create_inserts_zero_balance_snapshot() {
        serviceAt(T0).create(
                new CreateAccountCommand("Operating cash", new CurrencyCode("EUR"), AccountType.ASSET, false));
        assertThat(balances.balanceOf(new AccountId(ID)))
                .contains(new AccountBalance(new AccountId(ID), 0, 0, T0));
        assertThat(balances.insertZeroCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("I12: freeze acquires the account_balance lock — lifecycle and posting serialize on the same lock (ADR-0003)")
    void freeze_takes_the_balance_lock() {
        seededActive();
        serviceAt(T1).update(new UpdateAccountCommand(
                new AccountId(ID), Optional.empty(), Optional.of(AccountStatus.FROZEN)));
        assertThat(balances.lockInvocations()).containsExactly(List.of(new AccountId(ID)));
    }

    @Test
    @DisplayName("I12 (posting half): close with a nonzero natural balance is rejected under the lock, nothing written")
    void close_with_nonzero_balance_writes_nothing() {
        seededActive();
        balances.seed(new AccountBalance(new AccountId(ID), 50, 1, T0));
        AccountService service = serviceAt(T1);

        UpdateAccountCommand close = new UpdateAccountCommand(
                new AccountId(ID), Optional.empty(), Optional.of(AccountStatus.CLOSED));
        assertThatThrownBy(() -> service.update(close))
                .isInstanceOf(AccountBalanceNotZero.class)
                .hasFieldOrPropertyWithValue("naturalBalance", 50L);

        assertThat(balances.lockInvocations()).as("judged under the lock")
                .containsExactly(List.of(new AccountId(ID)));
        assertThat(repository.updateCalls()).isZero();
    }

    @Test
    @DisplayName("ADR-0001: close when natural has no 64-bit representation surfaces AmountOverflow, not a wrapped verdict")
    void close_at_long_min_value_raw_surfaces_amount_overflow() {
        // Reachable state: an allow-negative LIABILITY can post its raw balance down to
        // Long.MIN_VALUE (the overdraft judgment that would refuse it is skipped for
        // allow-negative accounts). Closing must then judge natural = raw × (−1), which has no
        // 64-bit representation — the checked multiply in AccountBalance.natural refuses, and
        // this use case translates the refusal into the 422 AmountOverflow instead of leaking
        // a 500 or, wrapped, calling an astronomically positive position "natural −2⁶³".
        Account liability = Account.open(new AccountId(ID), "Customer deposits",
                new CurrencyCode("EUR"), AccountType.LIABILITY, true, T0);
        repository.seed(liability);
        balances.seed(new AccountBalance(liability.id(), Long.MIN_VALUE, 1, T0));
        AccountService service = serviceAt(T1);

        UpdateAccountCommand close = new UpdateAccountCommand(
                new AccountId(ID), Optional.empty(), Optional.of(AccountStatus.CLOSED));
        assertThatThrownBy(() -> service.update(close)).isInstanceOf(AmountOverflow.class);
        assertThat(repository.updateCalls()).isZero();
    }

    @Test
    @DisplayName("I12: close at zero natural balance succeeds, decided under the lock")
    void close_at_zero_balance_succeeds() {
        seededActive();
        Account closed = serviceAt(T1).update(new UpdateAccountCommand(
                new AccountId(ID), Optional.empty(), Optional.of(AccountStatus.CLOSED)));
        assertThat(closed.status()).isEqualTo(AccountStatus.CLOSED);
        assertThat(balances.lockInvocations()).containsExactly(List.of(new AccountId(ID)));
        assertThat(repository.findById(new AccountId(ID))).contains(closed);
    }

    @Test
    @DisplayName("rename: a pure metadata edit takes NO balance lock — metadata races are the version guard's job (ADR-0003)")
    void rename_takes_no_balance_lock() {
        seededActive();
        serviceAt(T1).update(new UpdateAccountCommand(
                new AccountId(ID), Optional.of("Renamed cash"), Optional.empty()));
        assertThat(balances.lockInvocations()).isEmpty();
    }

    @Test
    @DisplayName("no-op: a same-state status PATCH returns before locking — M1's nothing-persisted contract holds")
    void noop_status_patch_takes_no_lock() {
        seededActive();
        serviceAt(T1).update(new UpdateAccountCommand(
                new AccountId(ID), Optional.empty(), Optional.of(AccountStatus.ACTIVE)));
        assertThat(balances.lockInvocations()).isEmpty();
        assertThat(repository.updateCalls()).isZero();
    }
}
