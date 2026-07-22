package io.github.essandhu.ledger.domain.model;

/**
 * The closed five-type chart of accounts (PLAN §1: EQUITY exists so opening balances have a
 * legitimate counter-leg). M2 adds the debit/credit direction ({@code +1} for ASSET/EXPENSE,
 * {@code −1} for the rest) when natural balances arrive.
 */
public enum AccountType {
    ASSET,
    LIABILITY,
    EQUITY,
    INCOME,
    EXPENSE
}
