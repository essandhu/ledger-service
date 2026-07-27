package io.github.essandhu.ledger.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import io.github.essandhu.ledger.domain.error.CurrencyMismatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-0001: money is a {@code long} count of minor units — exact, checked, currency-tagged.
 * These are the example-based unit proofs; the property suite replays the same laws over
 * generated values (commutativity, associativity, sign inversion, mismatch rejection,
 * overflow-never-wraps — the ADR's five-item proof list).
 */
@DisplayName("Money: exact checked minor-unit arithmetic (ADR-0001)")
class MoneyTest {

    private static final CurrencyCode EUR = new CurrencyCode("EUR");
    private static final CurrencyCode USD = new CurrencyCode("USD");
    private static final CurrencyCode JPY = new CurrencyCode("JPY");
    private static final CurrencyCode BHD = new CurrencyCode("BHD");

    @Nested
    @DisplayName("construction")
    class Construction {

        @Test
        @DisplayName("of() and zero() build currency-tagged amounts")
        void of_and_zero_build_tagged_amounts() {
            assertThat(Money.of(1099, EUR).amount()).isEqualTo(1099);
            assertThat(Money.of(1099, EUR).currency()).isEqualTo(EUR);
            assertThat(Money.zero(JPY).amount()).isZero();
            assertThat(Money.zero(JPY).isZero()).isTrue();
        }

        @Test
        @DisplayName("rejects a null currency")
        void rejects_null_currency() {
            assertThatThrownBy(() -> Money.of(1, null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("amounts are minor units at any exponent — JPY (0) and BHD (3) flow through unchanged")
        void minor_units_at_any_exponent() {
            // TEST-STRATEGY §2.2: nothing downstream may assume two decimal places. 500 is
            // ¥500 in JPY (exponent 0) and 0.500 dinar in BHD (exponent 3) — Money neither
            // knows nor cares; the exponent is presentation, not arithmetic.
            assertThat(Money.of(500, JPY).plus(Money.of(500, JPY))).isEqualTo(Money.of(1000, JPY));
            assertThat(Money.of(500, BHD).plus(Money.of(500, BHD))).isEqualTo(Money.of(1000, BHD));
        }
    }

    @Nested
    @DisplayName("plus: checked same-currency addition")
    class Plus {

        @Test
        @DisplayName("adds amounts in the same currency")
        void adds_same_currency_amounts() {
            assertThat(Money.of(1099, EUR).plus(Money.of(-99, EUR))).isEqualTo(Money.of(1000, EUR));
        }

        @Test
        @DisplayName("addition is commutative")
        void addition_is_commutative() {
            Money a = Money.of(37, EUR);
            Money b = Money.of(-1063, EUR);
            assertThat(a.plus(b)).isEqualTo(b.plus(a));
        }

        @Test
        @DisplayName("a + (−a) = zero — the sign-inversion law reversals depend on")
        void a_plus_its_negation_is_zero() {
            Money a = Money.of(123456789, BHD);
            assertThat(a.plus(a.negate())).isEqualTo(Money.zero(BHD));
        }

        @Test
        @DisplayName("rejects mixed currencies, reporting expected = receiver and actual = argument")
        void rejects_mixed_currencies() {
            Money eur = Money.of(100, EUR);
            Money usd = Money.of(100, USD);
            assertThatThrownBy(() -> eur.plus(usd))
                    .isInstanceOfSatisfying(CurrencyMismatch.class, error -> {
                        assertThat(error.expected()).isEqualTo(EUR);
                        assertThat(error.actual()).isEqualTo(USD);
                    });
            // The payload order is receiver-then-argument, so swapping the operands must swap
            // expected and actual — not merely re-report the same pair.
            assertThatThrownBy(() -> usd.plus(eur))
                    .isInstanceOfSatisfying(CurrencyMismatch.class, error -> {
                        assertThat(error.expected()).isEqualTo(USD);
                        assertThat(error.actual()).isEqualTo(EUR);
                    });
        }

        @Test
        @DisplayName("overflow past Long.MAX_VALUE throws ArithmeticException — never wraps")
        void overflow_throws_instead_of_wrapping() {
            Money max = Money.of(Long.MAX_VALUE, EUR);
            Money one = Money.of(1, EUR);
            assertThatThrownBy(() -> max.plus(one))
                    .isInstanceOf(ArithmeticException.class);
        }

        @Test
        @DisplayName("underflow past Long.MIN_VALUE throws ArithmeticException — never wraps")
        void underflow_throws_instead_of_wrapping() {
            Money min = Money.of(Long.MIN_VALUE, EUR);
            Money minusOne = Money.of(-1, EUR);
            assertThatThrownBy(() -> min.plus(minusOne))
                    .isInstanceOf(ArithmeticException.class);
        }
    }

    @Nested
    @DisplayName("negate: checked sign inversion")
    class Negate {

        @Test
        @DisplayName("negates the amount exactly, keeping the currency")
        void negates_exactly() {
            assertThat(Money.of(1099, EUR).negate()).isEqualTo(Money.of(-1099, EUR));
            assertThat(Money.of(-1099, EUR).negate()).isEqualTo(Money.of(1099, EUR));
            assertThat(Money.zero(EUR).negate()).isEqualTo(Money.zero(EUR));
        }

        @Test
        @DisplayName("negating Long.MIN_VALUE throws ArithmeticException — the asymmetric edge of two's complement")
        void negating_min_value_throws() {
            // −Long.MIN_VALUE is not representable in 64 bits; unchecked negation would
            // silently return Long.MIN_VALUE again. negateExact refuses.
            Money min = Money.of(Long.MIN_VALUE, EUR);
            assertThatThrownBy(min::negate)
                    .isInstanceOf(ArithmeticException.class);
        }
    }

    @Nested
    @DisplayName("queries")
    class Queries {

        @ParameterizedTest
        @CsvSource({"-42, -1", "0, 0", "42, 1"})
        @DisplayName("signum reports the sign of the amount")
        void signum_reports_sign(long amount, int expected) {
            assertThat(Money.of(amount, EUR).signum()).isEqualTo(expected);
        }

        @Test
        @DisplayName("isZero is true only at exactly zero")
        void is_zero_only_at_zero() {
            assertThat(Money.zero(EUR).isZero()).isTrue();
            assertThat(Money.of(1, EUR).isZero()).isFalse();
            assertThat(Money.of(-1, EUR).isZero()).isFalse();
        }
    }
}
