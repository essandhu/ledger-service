package io.github.essandhu.ledger.domain.model;

import java.util.Currency;

import io.github.essandhu.ledger.domain.error.InvalidAccountInput;

/**
 * An ISO-4217 currency that denominates actual money. Validated against the JDK's ISO table
 * (framework-free, per the domain rule of the hexagonal rules) with one extra guard: pseudo-currencies —
 * XXX (no currency), XTS (testing), precious metals XAU/XAG/XPT/XPD — carry
 * {@code defaultFractionDigits() == -1} and are rejected, because an account in them would be
 * semantically meaningless and M2's Money/exponent logic could not handle them.
 *
 * <p>Caveat, accepted deliberately: the JDK table can drift across CLDR updates and can be
 * extended per-host via {@code ${java.home}/lib/currency.properties}. For a single-deployment
 * service whose test and prod JDKs are pinned (Temurin 21), that risk is theoretical; owning a
 * currency table would be worse.
 */
public record CurrencyCode(String value) {

    public CurrencyCode {
        if (value == null || !value.matches("[A-Z]{3}")) {
            throw new InvalidAccountInput("currency must be a three-letter uppercase ISO 4217 code");
        }
        Currency currency;
        try {
            currency = Currency.getInstance(value);
        } catch (IllegalArgumentException unknownCode) {
            throw new InvalidAccountInput("unknown ISO 4217 currency: " + value);
        }
        if (currency.getDefaultFractionDigits() < 0) {
            throw new InvalidAccountInput(
                    "%s is an ISO 4217 pseudo-currency and cannot denominate an account".formatted(value));
        }
    }
}
