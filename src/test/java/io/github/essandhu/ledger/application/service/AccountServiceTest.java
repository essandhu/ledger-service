package io.github.essandhu.ledger.application.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
import io.github.essandhu.ledger.domain.error.AccountClosed;
import io.github.essandhu.ledger.domain.error.InvalidAccountInput;
import io.github.essandhu.ledger.domain.error.InvalidStatusTransition;
import io.github.essandhu.ledger.domain.model.Account;
import io.github.essandhu.ledger.domain.model.AccountId;
import io.github.essandhu.ledger.domain.model.AccountStatus;
import io.github.essandhu.ledger.domain.model.AccountType;
import io.github.essandhu.ledger.domain.model.CurrencyCode;
import io.github.essandhu.ledger.support.fakes.FakeAccountRepository;
import io.github.essandhu.ledger.support.fakes.FixedIdGenerator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Use-case orchestration over fakes: id from the generator port, time from the injected Clock,
 * atomic PATCH semantics (rename and status in one operation), and no-op detection — a PATCH
 * that changes nothing must not write (no version bump, no updated_at drift).
 */
@DisplayName("AccountService: use-case orchestration")
class AccountServiceTest {

    private static final Instant T0 = Instant.parse("2026-07-22T10:00:00Z");
    private static final Instant T1 = T0.plusSeconds(60);
    private static final UUID ID = UUID.fromString("019817b4-0000-7000-8000-000000000001");

    private final FakeAccountRepository repository = new FakeAccountRepository();

    private AccountService serviceAt(Instant now) {
        return new AccountService(repository, new FixedIdGenerator(ID), Clock.fixed(now, ZoneOffset.UTC));
    }

    private Account seededActive() {
        Account account = Account.open(new AccountId(ID), "Operating cash", new CurrencyCode("EUR"),
                AccountType.ASSET, false, T0);
        repository.seed(account);
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
        AccountService seeded = new AccountService(repository, new FixedIdGenerator(ID, id2),
                Clock.fixed(T0, ZoneOffset.UTC));
        seeded.create(new CreateAccountCommand("A", new CurrencyCode("EUR"), AccountType.ASSET, false));
        seeded.create(new CreateAccountCommand("B", new CurrencyCode("EUR"), AccountType.EQUITY, true));

        AccountPage all = service.list(AccountFilter.none(), new PageSpec(0, 20));
        assertThat(all.content()).extracting(Account::name).containsExactly("A", "B");
        assertThat(all.totalElements()).isEqualTo(2);

        AccountPage equity = service.list(
                new AccountFilter(Optional.of(AccountType.EQUITY), Optional.empty()), new PageSpec(0, 20));
        assertThat(equity.content()).extracting(Account::name).containsExactly("B");
    }
}
