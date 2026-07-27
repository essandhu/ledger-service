package io.github.essandhu.ledger.domain.model;

import java.util.Objects;

import io.github.essandhu.ledger.domain.error.CurrencyMismatch;

/**
 * An exact amount of one currency, counted in minor units (ADR-0001): a {@code long} plus a
 * {@link CurrencyCode} — no float, no double, no BigDecimal. The exponent (JPY = 0, EUR = 2,
 * BHD = 3) is presentation owned by clients; arithmetic here neither knows nor cares, which is
 * exactly why nothing downstream may assume two decimal places.
 *
 * <p>All arithmetic is checked: {@link #plus} and {@link #negate} go through
 * {@link Math#addExact} / {@link Math#negateExact}, so 64-bit overflow surfaces as
 * {@link ArithmeticException} — never a wrapped number (ADR-0001's proof list; the property
 * suite replays commutativity, associativity, sign inversion, mismatch rejection, and
 * overflow-never-wraps over generated values). The exception deliberately propagates raw from
 * this type: translating it into a client-facing rejection ({@code AmountOverflow}) is the
 * accumulation points' job, where there is context to report.
 *
 * <p>Money never crosses currencies implicitly: {@link #plus} on mixed currencies throws
 * {@link CurrencyMismatch}. There is no exchange, no conversion, no common denominator —
 * per-currency zero-sum (I1) makes multi-currency entries legal without any of them.
 */
public record Money(long amount, CurrencyCode currency) {

    public Money {
        Objects.requireNonNull(currency, "currency");
    }

    /** Reads as {@code Money.of(1099, EUR)} — the canonical constructor in factory clothing. */
    public static Money of(long amount, CurrencyCode currency) {
        return new Money(amount, currency);
    }

    /** The additive identity in {@code currency} — what every balanced entry sums to (I1). */
    public static Money zero(CurrencyCode currency) {
        return new Money(0, currency);
    }

    /**
     * Checked same-currency addition. Mixed currencies throw {@link CurrencyMismatch}; a sum
     * outside 64-bit minor units throws {@link ArithmeticException} rather than wrapping.
     */
    public Money plus(Money other) {
        Objects.requireNonNull(other, "other");
        if (!currency.equals(other.currency)) {
            throw new CurrencyMismatch(currency, other.currency);
        }
        return new Money(Math.addExact(amount, other.amount), currency);
    }

    /**
     * Checked sign inversion — the arithmetic under every reversal leg (I11). Checked because
     * of the asymmetric edge of two's complement: {@code −Long.MIN_VALUE} does not exist in
     * 64 bits, and unchecked negation would silently return {@code Long.MIN_VALUE} again.
     */
    public Money negate() {
        return new Money(Math.negateExact(amount), currency);
    }

    public boolean isZero() {
        return amount == 0;
    }

    /** −1, 0, or +1 — under the debit-positive convention, +1 means a debit leg. */
    public int signum() {
        return Long.signum(amount);
    }
}
