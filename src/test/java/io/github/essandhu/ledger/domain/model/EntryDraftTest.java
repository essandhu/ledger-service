package io.github.essandhu.ledger.domain.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.github.essandhu.ledger.domain.error.AmountOverflow;
import io.github.essandhu.ledger.domain.error.InvalidEntryInput;
import io.github.essandhu.ledger.domain.error.TooFewPostings;
import io.github.essandhu.ledger.domain.error.UnbalancedEntry;
import io.github.essandhu.ledger.domain.error.ZeroAmountPosting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * I1 (zero-sum per currency, exact integer equality) and I2 (≥2 legs, every leg nonzero) proven
 * at construction of the intent, before any I/O. The validation ORDER is part of the contract —
 * the earliest broken rule wins, so problem responses and metric reason tags are deterministic —
 * and is pinned by the "validation order" nest below. The property suite replays I1/I2 over
 * generated balanced/perturbed drafts, JPY and BHD included.
 */
@DisplayName("I1/I2: entry drafts balance to exactly zero per currency across ≥2 nonzero legs")
class EntryDraftTest {

    private static final CurrencyCode EUR = new CurrencyCode("EUR");
    private static final CurrencyCode JPY = new CurrencyCode("JPY");

    private static final AccountId CASH =
            new AccountId(UUID.fromString("019817b4-0000-7000-8000-00000000000a"));
    private static final AccountId REVENUE =
            new AccountId(UUID.fromString("019817b4-0000-7000-8000-00000000000b"));
    private static final AccountId VAT =
            new AccountId(UUID.fromString("019817b4-0000-7000-8000-00000000000c"));

    private static EntryDraft.Leg leg(AccountId accountId, long amount, CurrencyCode currency) {
        return new EntryDraft.Leg(accountId, Money.of(amount, currency));
    }

    @Nested
    @DisplayName("I1: zero-sum per currency")
    class ZeroSum {

        @Test
        @DisplayName("accepts a balanced two-leg draft, preserving leg order")
        void accepts_balanced_two_leg_draft() {
            EntryDraft draft = new EntryDraft("invoice 42",
                    List.of(leg(CASH, 1099, EUR), leg(REVENUE, -1099, EUR)));
            assertThat(draft.description()).isEqualTo("invoice 42");
            assertThat(draft.legs()).containsExactly(
                    leg(CASH, 1099, EUR), leg(REVENUE, -1099, EUR));
        }

        @Test
        @DisplayName("accepts a multi-currency draft when EACH currency nets to zero")
        void accepts_multi_currency_draft_balanced_per_currency() {
            // The v1 scope: multi-currency entries are legal without any notion of an exchange
            // rate precisely because balance is judged per currency, never across.
            assertThatCode(() -> new EntryDraft(null, List.of(
                    leg(CASH, 500, EUR), leg(REVENUE, -500, EUR),
                    leg(CASH, 300, JPY), leg(REVENUE, -300, JPY))))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("rejects an unbalanced draft, reporting the per-currency residual")
        void rejects_unbalanced_draft_with_residuals() {
            assertThatThrownBy(() -> new EntryDraft(null,
                    List.of(leg(CASH, 1099, EUR), leg(REVENUE, -1094, EUR))))
                    .isInstanceOf(UnbalancedEntry.class)
                    .hasFieldOrPropertyWithValue("residuals", Map.of(EUR, 5L));
        }

        @Test
        @DisplayName("reports only the currencies that fail to balance")
        void reports_only_imbalanced_currencies() {
            assertThatThrownBy(() -> new EntryDraft(null, List.of(
                    leg(CASH, 500, EUR), leg(REVENUE, -500, EUR),
                    leg(CASH, 300, JPY), leg(REVENUE, -303, JPY))))
                    .isInstanceOf(UnbalancedEntry.class)
                    .hasFieldOrPropertyWithValue("residuals", Map.of(JPY, -3L));
        }

        @Test
        @DisplayName("balance is exact integer equality — one minor unit off is unbalanced")
        void one_minor_unit_off_is_unbalanced() {
            assertThatThrownBy(() -> new EntryDraft(null,
                    List.of(leg(CASH, 1, JPY), leg(REVENUE, -2, JPY))))
                    .isInstanceOf(UnbalancedEntry.class);
        }

        @Test
        @DisplayName("a running sum that overflows long is AmountOverflow, never a wrapped total")
        void running_sum_overflow_is_amount_overflow() {
            // Mathematically these four legs net to zero, but the checked accumulation
            // overflows at the second leg. ADR-0001: checked arithmetic surfaces as a
            // rejection — never a silently wrapped number that happens to look balanced.
            assertThatThrownBy(() -> new EntryDraft(null, List.of(
                    leg(CASH, Long.MAX_VALUE, EUR), leg(REVENUE, 1, EUR),
                    leg(CASH, -Long.MAX_VALUE, EUR), leg(REVENUE, -1, EUR))))
                    .isInstanceOf(AmountOverflow.class);
        }
    }

    @Nested
    @DisplayName("I2: leg count and nonzero amounts")
    class LegRules {

        @Test
        @DisplayName("rejects an empty draft, reporting a count of zero — double-entry needs two sides")
        void rejects_empty_legs() {
            // Asserted through the accessor, not field reflection: count() is the error's
            // structured contract, and only a real call pins it.
            assertThatThrownBy(() -> new EntryDraft(null, List.of()))
                    .isInstanceOfSatisfying(TooFewPostings.class,
                            error -> assertThat(error.count()).isZero());
        }

        @Test
        @DisplayName("rejects a single-leg draft, reporting the offending count — it would create or destroy value")
        void rejects_single_leg() {
            assertThatThrownBy(() -> new EntryDraft(null, List.of(leg(CASH, 100, EUR))))
                    .isInstanceOfSatisfying(TooFewPostings.class,
                            error -> assertThat(error.count()).isEqualTo(1));
        }

        @Test
        @DisplayName("rejects a zero-amount leg, naming the offending account")
        void rejects_zero_amount_leg() {
            assertThatThrownBy(() -> new EntryDraft(null, List.of(
                    leg(CASH, 100, EUR), leg(VAT, 0, EUR), leg(REVENUE, -100, EUR))))
                    .isInstanceOf(ZeroAmountPosting.class)
                    .hasFieldOrPropertyWithValue("accountId", VAT);
        }
    }

    @Nested
    @DisplayName("description rules (nullable; entry-sized, not name-sized)")
    class Description {

        @Test
        @DisplayName("null description is legal — absence means absence")
        void accepts_null_description() {
            EntryDraft draft = new EntryDraft(null,
                    List.of(leg(CASH, 1, EUR), leg(REVENUE, -1, EUR)));
            assertThat(draft.description()).isNull();
        }

        @Test
        @DisplayName("accepts exactly 500 characters")
        void accepts_500_characters() {
            assertThatCode(() -> new EntryDraft("x".repeat(500),
                    List.of(leg(CASH, 1, EUR), leg(REVENUE, -1, EUR))))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("rejects blank, over-500, and control-character descriptions")
        void rejects_invalid_descriptions() {
            List<EntryDraft.Leg> balanced = List.of(leg(CASH, 1, EUR), leg(REVENUE, -1, EUR));
            assertThatThrownBy(() -> new EntryDraft("   ", balanced))
                    .isInstanceOf(InvalidEntryInput.class);
            assertThatThrownBy(() -> new EntryDraft("x".repeat(501), balanced))
                    .isInstanceOf(InvalidEntryInput.class);
            // U+0000 is legal JSON but not storable in PostgreSQL text; without the domain
            // guard it would be a 500 at the JDBC boundary instead of a 400 here.
            assertThatThrownBy(() -> new EntryDraft("a" + "\u0000" + "b", balanced))
                    .isInstanceOf(InvalidEntryInput.class);
            assertThatThrownBy(() -> new EntryDraft("a\tb", balanced))
                    .isInstanceOf(InvalidEntryInput.class);
        }
    }

    @Nested
    @DisplayName("validation order: the earliest broken rule wins")
    class ValidationOrder {

        @Test
        @DisplayName("null legs outrank everything — even an invalid description")
        void null_legs_first() {
            assertThatThrownBy(() -> new EntryDraft("   ", null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("leg count outranks the description rules")
        void leg_count_before_description() {
            assertThatThrownBy(() -> new EntryDraft("   ", List.of(leg(CASH, 100, EUR))))
                    .isInstanceOf(TooFewPostings.class);
        }

        @Test
        @DisplayName("per-leg nonzero outranks the description rules")
        void zero_leg_before_description() {
            assertThatThrownBy(() -> new EntryDraft("   ",
                    List.of(leg(CASH, 0, EUR), leg(REVENUE, -100, EUR))))
                    .isInstanceOf(ZeroAmountPosting.class);
        }

        @Test
        @DisplayName("description rules outrank the zero-sum check")
        void description_before_imbalance() {
            assertThatThrownBy(() -> new EntryDraft("   ",
                    List.of(leg(CASH, 100, EUR), leg(REVENUE, -1, EUR))))
                    .isInstanceOf(InvalidEntryInput.class);
        }

        @Test
        @DisplayName("per-leg nonzero outranks the zero-sum check")
        void zero_leg_before_imbalance() {
            assertThatThrownBy(() -> new EntryDraft(null,
                    List.of(leg(CASH, 0, EUR), leg(REVENUE, -100, EUR))))
                    .isInstanceOf(ZeroAmountPosting.class);
        }
    }

    @Nested
    @DisplayName("shape and defensiveness")
    class Shape {

        @Test
        @DisplayName("rejects a null leg element")
        void rejects_null_leg_element() {
            List<EntryDraft.Leg> legs = new ArrayList<>();
            legs.add(leg(CASH, 1, EUR));
            legs.add(null);
            assertThatThrownBy(() -> new EntryDraft(null, legs))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("a leg rejects null components")
        void leg_rejects_nulls() {
            assertThatThrownBy(() -> new EntryDraft.Leg(null, Money.of(1, EUR)))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new EntryDraft.Leg(CASH, null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("the legs list is defensively copied and immutable")
        void legs_are_defensively_copied() {
            List<EntryDraft.Leg> source = new ArrayList<>(
                    List.of(leg(CASH, 1, EUR), leg(REVENUE, -1, EUR)));
            EntryDraft draft = new EntryDraft(null, source);
            source.clear();
            assertThat(draft.legs()).hasSize(2);
            assertThatThrownBy(() -> draft.legs().add(leg(VAT, 1, EUR)))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
