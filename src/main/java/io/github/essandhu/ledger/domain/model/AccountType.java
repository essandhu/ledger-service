package io.github.essandhu.ledger.domain.model;

/**
 * The closed five-type chart of accounts (PLAN §1: EQUITY exists so opening balances have a
 * legitimate counter-leg), carrying the debit/credit direction ({@code +1} for ASSET/EXPENSE,
 * {@code −1} for the rest) now that natural balances have arrived (M2, PLAN §4.2).
 */
public enum AccountType {
    ASSET,
    LIABILITY,
    EQUITY,
    INCOME,
    EXPENSE;

    /**
     * The sign that turns a raw debit-positive balance into the natural balance a bookkeeper
     * expects: {@code natural = raw × direction(type)} (PLAN §4.2, the formula ADR-0002's
     * snapshot reads rely on). Debit-normal types (ASSET, EXPENSE) grow with debits, so
     * {@code +1}; credit-normal types (LIABILITY, EQUITY, INCOME) grow with credits, so
     * {@code −1}. Exhaustive switch: adding a sixth type without deciding its direction is a
     * compile error, not a silent {@code +1}.
     */
    public int direction() {
        return switch (this) {
            case ASSET, EXPENSE -> 1;
            case LIABILITY, EQUITY, INCOME -> -1;
        };
    }

    /**
     * THE natural-balance formula, in one place (M3 dedup — it was inlined at three call
     * sites): {@code natural = raw × direction} (PLAN §4.2), CHECKED. Direction is ±1, so the
     * product is exact for every representable value bar the two's-complement edge: raw
     * {@code Long.MIN_VALUE} on a credit-normal type has no 64-bit natural, and a plain
     * {@code *} would silently return {@code Long.MIN_VALUE} again — a NEGATIVE verdict for
     * an astronomically positive position. The raw {@link ArithmeticException} deliberately
     * propagates; translating it into the client-facing {@code AmountOverflow} is the
     * accumulation points' job (the contract {@link Money} pins for checked arithmetic).
     */
    public long natural(long raw) {
        return Math.multiplyExact(raw, direction());
    }
}
