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
}
