package io.github.essandhu.ledger.console.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.essandhu.ledger.console.api.LedgerApi.Money;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The money presentation contract (M8b): the console owns the ISO-4217 exponent the core is
 * deliberately blind to. Golden strings — fixed en-US style, so these are byte-stable across
 * JDK/locale-data upgrades.
 */
@DisplayName("MoneyFormat (M8b): minor units → display, exponent per ISO-4217, no floating point")
class MoneyFormatTest {

    @Test
    @DisplayName("the ADR-0001 exponent table: JPY 0, EUR 2, BHD 3")
    void exponents_come_from_iso4217() {
        assertThat(MoneyFormat.format(new Money(12345, "JPY"))).isEqualTo("12,345 JPY");
        assertThat(MoneyFormat.format(new Money(12345, "EUR"))).isEqualTo("123.45 EUR");
        assertThat(MoneyFormat.format(new Money(12345, "BHD"))).isEqualTo("12.345 BHD");
    }

    @Test
    @DisplayName("sub-unit amounts zero-pad — 5 minor units of EUR is 0.05, not .5")
    void sub_unit_amounts_pad() {
        assertThat(MoneyFormat.format(new Money(5, "EUR"))).isEqualTo("0.05 EUR");
        assertThat(MoneyFormat.format(new Money(-5, "EUR"))).isEqualTo("-0.05 EUR");
        assertThat(MoneyFormat.format(new Money(7, "BHD"))).isEqualTo("0.007 BHD");
    }

    @Test
    @DisplayName("zero is zero in every exponent")
    void zero() {
        assertThat(MoneyFormat.format(new Money(0, "EUR"))).isEqualTo("0.00 EUR");
        assertThat(MoneyFormat.format(new Money(0, "JPY"))).isEqualTo("0 JPY");
    }

    @Test
    @DisplayName("negatives keep their sign even when the integer part is zero")
    void negative_sub_unit_keeps_sign() {
        // The quotient/remainder formulation loses this sign (-50/100 == 0); the string
        // route is why the test exists.
        assertThat(MoneyFormat.format(new Money(-50, "EUR"))).isEqualTo("-0.50 EUR");
    }

    @Test
    @DisplayName("grouping: commas every three integer digits, en-US style")
    void grouping() {
        assertThat(MoneyFormat.format(new Money(123456789, "EUR"))).isEqualTo("1,234,567.89 EUR");
        assertThat(MoneyFormat.format(new Money(1234567, "JPY"))).isEqualTo("1,234,567 JPY");
        assertThat(MoneyFormat.format(new Money(-100000, "EUR"))).isEqualTo("-1,000.00 EUR");
    }

    @Test
    @DisplayName("the Long edges survive — Math.abs would corrupt MIN_VALUE silently")
    void long_edges() {
        assertThat(MoneyFormat.format(new Money(Long.MIN_VALUE, "EUR")))
                .isEqualTo("-92,233,720,368,547,758.08 EUR");
        assertThat(MoneyFormat.format(new Money(Long.MAX_VALUE, "EUR")))
                .isEqualTo("92,233,720,368,547,758.07 EUR");
    }

    @Test
    @DisplayName("unknown and pseudo-currency codes fall back to raw minor units, loudly labeled")
    void unknown_codes_fall_back() {
        // Unreachable via ledger data (the core rejects both at account creation) but the
        // renderer must not produce a wrong decimal string if it ever sees one.
        assertThat(MoneyFormat.format(new Money(12345, "ZZZ")))
                .isEqualTo("12345 ZZZ (minor units)");
        // XXX is a VALID ISO code whose exponent is -1 — the trap is the exponent, not the lookup.
        assertThat(MoneyFormat.format(new Money(12345, "XXX")))
                .isEqualTo("12345 XXX (minor units)");
    }
}
