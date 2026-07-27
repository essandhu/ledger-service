package io.github.essandhu.ledger.domain.model;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import io.github.essandhu.ledger.domain.error.AccountBalanceNotZero;
import io.github.essandhu.ledger.domain.error.AccountClosed;
import io.github.essandhu.ledger.domain.error.AccountFrozen;
import io.github.essandhu.ledger.domain.error.InvalidAccountInput;
import io.github.essandhu.ledger.domain.error.InvalidStatusTransition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * I12 (lifecycle half, M1): {@code ACTIVE ⇄ FROZEN}, {@code ACTIVE|FROZEN → CLOSED}, CLOSED
 * terminal. Same-state transitions are deliberate no-ops (declarative PATCH — the
 * account-management API carries no Idempotency-Key, so natural idempotence is its only retry
 * story). The posting half of I12 (M2) is proven here at the unit level too: posting acceptance
 * by status ({@code ensureAcceptsPostings}) and the zero-balance close precondition
 * ({@code CloseBalanceRule}); the lock discipline around both is the integration suite's job.
 */
@DisplayName("I12: account state machine, posting acceptance, and the close-balance rule")
class AccountTest {

    private static final Instant T0 = Instant.parse("2026-07-22T10:00:00Z");
    private static final Instant T1 = T0.plusSeconds(60);

    private static Account active() {
        return Account.open(new AccountId(UUID.fromString("019817b4-0000-7000-8000-000000000001")),
                "Operating cash", new CurrencyCode("EUR"), AccountType.ASSET, false, T0);
    }

    private static Account frozen() {
        return active().transitionTo(AccountStatus.FROZEN, T0);
    }

    private static Account closed() {
        return active().transitionTo(AccountStatus.CLOSED, T0);
    }

    @Nested
    @DisplayName("opening")
    class Opening {

        @Test
        @DisplayName("a new account is ACTIVE with created_at = updated_at = now")
        void opens_active_with_clock_timestamps() {
            Account account = active();
            assertThat(account.status()).isEqualTo(AccountStatus.ACTIVE);
            assertThat(account.createdAt()).isEqualTo(T0);
            assertThat(account.updatedAt()).isEqualTo(T0);
            assertThat(account.name()).isEqualTo("Operating cash");
            assertThat(account.currency()).isEqualTo(new CurrencyCode("EUR"));
            assertThat(account.type()).isEqualTo(AccountType.ASSET);
            assertThat(account.allowNegative()).isFalse();
        }

        @Test
        @DisplayName("rejects blank and over-long names")
        void rejects_invalid_names() {
            AccountId id = new AccountId(UUID.randomUUID());
            CurrencyCode eur = new CurrencyCode("EUR");
            assertThatThrownBy(() -> Account.open(id, "   ", eur, AccountType.ASSET, false, T0))
                    .isInstanceOf(InvalidAccountInput.class);
            assertThatThrownBy(() -> Account.open(id, null, eur, AccountType.ASSET, false, T0))
                    .isInstanceOf(InvalidAccountInput.class);
            assertThatThrownBy(() -> Account.open(id, "x".repeat(201), eur, AccountType.ASSET, false, T0))
                    .isInstanceOf(InvalidAccountInput.class);
        }
    }

    @Nested
    @DisplayName("status transitions (the I12 lifecycle matrix)")
    class Transitions {

        @ParameterizedTest
        @CsvSource({
                "ACTIVE, FROZEN",
                "ACTIVE, CLOSED",
                "FROZEN, ACTIVE",
                "FROZEN, CLOSED",
        })
        @DisplayName("legal edges move the status and bump updated_at only")
        void legal_edges(AccountStatus from, AccountStatus to) {
            Account account = from == AccountStatus.ACTIVE ? active() : frozen();
            Account moved = account.transitionTo(to, T1);
            assertThat(moved.status()).isEqualTo(to);
            assertThat(moved.updatedAt()).isEqualTo(T1);
            assertThat(moved.createdAt()).isEqualTo(T0);
            assertThat(moved.name()).isEqualTo(account.name());
        }

        @ParameterizedTest
        @CsvSource({"ACTIVE", "FROZEN", "CLOSED"})
        @DisplayName("same-state transitions are no-ops: same instance, no timestamp bump")
        void self_loops_are_noops(AccountStatus status) {
            Account account = switch (status) {
                case ACTIVE -> active();
                case FROZEN -> frozen();
                case CLOSED -> closed();
            };
            Account result = account.transitionTo(status, T1);
            assertThat(result).isSameAs(account);
            assertThat(result.updatedAt()).isEqualTo(T0);
        }

        @ParameterizedTest
        @CsvSource({"ACTIVE", "FROZEN"})
        @DisplayName("CLOSED is terminal: no edge leaves it, and the error names both states")
        void closed_is_terminal(AccountStatus target) {
            Account account = closed();
            // Asserted through the accessors, not field reflection: from()/to() are the
            // error's structured contract, and only a real call pins it.
            assertThatThrownBy(() -> account.transitionTo(target, T1))
                    .isInstanceOfSatisfying(InvalidStatusTransition.class, error -> {
                        assertThat(error.from()).isEqualTo(AccountStatus.CLOSED);
                        assertThat(error.to()).isEqualTo(target);
                    });
        }
    }

    @Nested
    @DisplayName("rename")
    class Rename {

        @Test
        @DisplayName("renames on ACTIVE, bumping updated_at only")
        void renames_on_active() {
            Account renamed = active().rename("Petty cash", T1);
            assertThat(renamed.name()).isEqualTo("Petty cash");
            assertThat(renamed.updatedAt()).isEqualTo(T1);
            assertThat(renamed.createdAt()).isEqualTo(T0);
            assertThat(renamed.status()).isEqualTo(AccountStatus.ACTIVE);
        }

        @Test
        @DisplayName("renames on FROZEN — freezing blocks postings, not metadata")
        void renames_on_frozen() {
            Account renamed = frozen().rename("Petty cash", T1);
            assertThat(renamed.name()).isEqualTo("Petty cash");
            assertThat(renamed.status()).isEqualTo(AccountStatus.FROZEN);
        }

        @Test
        @DisplayName("rejects rename on CLOSED — terminal means terminal")
        void rejects_rename_on_closed() {
            Account account = closed();
            assertThatThrownBy(() -> account.rename("Petty cash", T1))
                    .isInstanceOf(AccountClosed.class);
        }

        @Test
        @DisplayName("rename to the current name is a no-op: same instance, no timestamp bump")
        void rename_to_same_name_is_noop() {
            Account account = active();
            assertThat(account.rename("Operating cash", T1)).isSameAs(account);
        }

        @Test
        @DisplayName("rename to the current name is a no-op even on CLOSED — retried PATCHes must not 422")
        void rename_to_same_name_is_noop_on_closed() {
            // The retry story: a combined rename+close PATCH succeeds, the response is lost,
            // the client retries the identical request — it finds the account already renamed
            // and closed, and must get the same 200 no-op, not account-closed.
            Account account = closed();
            assertThat(account.rename("Operating cash", T1)).isSameAs(account);
        }

        @Test
        @DisplayName("rejects blank, over-long, and control-character names")
        void rejects_invalid_names() {
            Account account = active();
            assertThatThrownBy(() -> account.rename(" ", T1)).isInstanceOf(InvalidAccountInput.class);
            assertThatThrownBy(() -> account.rename(null, T1)).isInstanceOf(InvalidAccountInput.class);
            assertThatThrownBy(() -> account.rename("x".repeat(201), T1))
                    .isInstanceOf(InvalidAccountInput.class);
            // U+0000 is legal JSON but not storable in PostgreSQL text; without the domain
            // guard it would be a 500 at the JDBC boundary instead of a 400 here.
            assertThatThrownBy(() -> account.rename("a" + "\u0000" + "b", T1))
                    .isInstanceOf(InvalidAccountInput.class);
            assertThatThrownBy(() -> account.rename("a\tb", T1))
                    .isInstanceOf(InvalidAccountInput.class);
        }
    }

    @Nested
    @DisplayName("direction: the debit/credit sign of each account type")
    class Direction {

        @ParameterizedTest
        @CsvSource({
                "ASSET, 1",
                "EXPENSE, 1",
                "LIABILITY, -1",
                "EQUITY, -1",
                "INCOME, -1",
        })
        @DisplayName("ASSET/EXPENSE are +1; LIABILITY/EQUITY/INCOME are −1")
        void direction_by_type(AccountType type, int expected) {
            assertThat(type.direction()).isEqualTo(expected);
        }
    }

    @Nested
    @DisplayName("I12 (posting half): posting acceptance by status")
    class PostingAcceptance {

        @Test
        @DisplayName("ACTIVE accounts accept postings")
        void active_accepts_postings() {
            assertThatCode(() -> active().ensureAcceptsPostings())
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("FROZEN rejects postings in both directions — reversibly, unlike CLOSED")
        void frozen_rejects_postings() {
            Account account = frozen();
            assertThatThrownBy(account::ensureAcceptsPostings)
                    .isInstanceOf(AccountFrozen.class);
        }

        @Test
        @DisplayName("CLOSED rejects postings — the same 422 family as metadata edits")
        void closed_rejects_postings() {
            Account account = closed();
            assertThatThrownBy(account::ensureAcceptsPostings)
                    .isInstanceOf(AccountClosed.class);
        }
    }

    @Nested
    @DisplayName("I12 (posting half): close requires a zero NATURAL balance")
    class CloseRule {

        private AccountBalance snapshot(Account account, long raw) {
            return new AccountBalance(account.id(), raw, 1, T0);
        }

        @ParameterizedTest
        @CsvSource({
                "ASSET, 250, 250",
                "EXPENSE, 250, 250",
                "LIABILITY, 250, -250",
                "EQUITY, 250, -250",
                "INCOME, -250, 250",
        })
        @DisplayName("natural balance = raw signed balance × direction")
        void natural_balance_is_raw_times_direction(AccountType type, long raw, long expected) {
            AccountBalance balance = new AccountBalance(
                    new AccountId(UUID.fromString("019817b4-0000-7000-8000-000000000002")), raw, 1, T0);
            assertThat(balance.natural(type)).isEqualTo(expected);
        }

        @Test
        @DisplayName("a zero natural balance may close")
        void zero_natural_balance_may_close() {
            Account account = active();
            assertThatCode(() -> CloseBalanceRule.ensureZeroForClose(snapshot(account, 0), account.type()))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a nonzero natural balance rejects close, reporting account and residual")
        void nonzero_natural_balance_rejects_close() {
            Account account = active();
            assertThatThrownBy(() -> CloseBalanceRule.ensureZeroForClose(snapshot(account, 250), account.type()))
                    .isInstanceOfSatisfying(AccountBalanceNotZero.class, error -> {
                        assertThat(error.accountId()).isEqualTo(account.id());
                        assertThat(error.naturalBalance()).isEqualTo(250L);
                    });
        }

        @Test
        @DisplayName("a LIABILITY with negative raw balance is equally unclosable — the rule reads natural, not raw")
        void liability_with_negative_raw_is_unclosable() {
            // Raw −5 on a LIABILITY is natural +5: the bank still owes someone. Closing must
            // judge the natural balance, or credit-normal accounts could close while carrying value.
            Account liability = Account.open(
                    new AccountId(UUID.fromString("019817b4-0000-7000-8000-000000000003")),
                    "Customer deposits", new CurrencyCode("EUR"), AccountType.LIABILITY, false, T0);
            // naturalBalance() must report +5 (natural), not −5 (raw) — the payload the caller
            // must clear speaks the same sign convention the rule judged.
            assertThatThrownBy(() -> CloseBalanceRule.ensureZeroForClose(snapshot(liability, -5), liability.type()))
                    .isInstanceOfSatisfying(AccountBalanceNotZero.class,
                            error -> assertThat(error.naturalBalance()).isEqualTo(5L));
        }

        @Test
        @DisplayName("the snapshot value rejects a negative posting count — the watermark only grows")
        void snapshot_rejects_negative_posting_count() {
            AccountId id = new AccountId(UUID.fromString("019817b4-0000-7000-8000-000000000004"));
            assertThatThrownBy(() -> new AccountBalance(id, 0, -1, T0))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("ADR-0001: natural at raw Long.MIN_VALUE throws ArithmeticException — never a wrapped sign")
        void natural_at_long_min_value_never_wraps() {
            // −Long.MIN_VALUE does not exist in 64 bits: raw × (−1) on a credit-normal account
            // would silently return Long.MIN_VALUE again under plain *, reporting a NEGATIVE
            // natural for an astronomically POSITIVE one. Checked multiply refuses; translating
            // the refusal into the 422 AmountOverflow is the accumulation points' job — the
            // same propagation contract Money pins for its own checked arithmetic.
            AccountBalance balance = new AccountBalance(
                    new AccountId(UUID.fromString("019817b4-0000-7000-8000-000000000005")),
                    Long.MIN_VALUE, 1, T0);
            assertThatThrownBy(() -> balance.natural(AccountType.LIABILITY))
                    .isInstanceOf(ArithmeticException.class);
        }
    }
}
