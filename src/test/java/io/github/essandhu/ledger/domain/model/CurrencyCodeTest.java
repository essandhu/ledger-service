package io.github.essandhu.ledger.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.essandhu.ledger.domain.error.InvalidAccountInput;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Currency is a real, ISO-4217, money currency — not merely three uppercase letters. The
 * exponent spread (JPY = 0, BHD = 3) is deliberately included so nothing downstream can assume
 * two decimal places (TEST-STRATEGY §2.2 makes the same point for the M2 generators).
 */
@DisplayName("CurrencyCode: ISO 4217 money currencies only")
class CurrencyCodeTest {

    @ParameterizedTest
    @ValueSource(strings = {"USD", "EUR", "JPY", "BHD"})
    @DisplayName("accepts real ISO 4217 currencies across exponents 0..3")
    void accepts_real_iso_currencies(String code) {
        assertThat(new CurrencyCode(code).value()).isEqualTo(code);
    }

    @ParameterizedTest
    @ValueSource(strings = {"XXX", "XTS", "XAU", "XPD"})
    @DisplayName("rejects ISO pseudo-currencies (no-currency, testing, precious metals)")
    void rejects_pseudo_currencies(String code) {
        // These are in the JDK's ISO table but have defaultFractionDigits == -1: they denominate
        // no money and an account in them would be semantically meaningless.
        assertThatThrownBy(() -> new CurrencyCode(code))
                .isInstanceOf(InvalidAccountInput.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ZZZ", "eur", "EURO", "EU", "E U", ""})
    @DisplayName("rejects non-ISO, wrong-case, and wrong-shape codes")
    void rejects_malformed_codes(String code) {
        assertThatThrownBy(() -> new CurrencyCode(code))
                .isInstanceOf(InvalidAccountInput.class);
    }

    @Test
    @DisplayName("rejects null")
    void rejects_null() {
        assertThatThrownBy(() -> new CurrencyCode(null))
                .isInstanceOf(InvalidAccountInput.class);
    }
}
