package io.github.essandhu.ledger.domain.model;

import java.util.Objects;

import io.github.essandhu.ledger.domain.error.AccountBalanceNotZero;

/**
 * I12, posting half of closing: an account may close only at natural balance zero.
 * A separate rule type rather than a new {@code Account} method — deliberately honoring the M1
 * forward-contract that {@code Account}'s signatures do not change for this rule, and
 * structurally necessary anyway: the rule reads the balance snapshot, which the account value
 * does not carry. The close use case evaluates it while holding the account_balance lock
 * (ADR-0003: lifecycle and posting serialize on the same lock, so no post can slip between
 * this check and the CLOSED write).
 *
 * <p>The rule reads the NATURAL balance, {@code raw × direction} — a LIABILITY at raw −5 is
 * natural +5, value still owed, equally unclosable. Judging raw would let credit-normal
 * accounts close while carrying value.
 */
public final class CloseBalanceRule {

    private CloseBalanceRule() {
        // Rule holder: two inputs, one judgment, no state. Not instantiable.
    }

    /**
     * @throws AccountBalanceNotZero when the natural balance is nonzero (422)
     * @throws ArithmeticException when the natural balance itself has no 64-bit representation
     *         (raw {@code Long.MIN_VALUE} on a credit-normal account — propagated raw from
     *         {@link AccountBalance#natural}; the close use case translates it into the 422
     *         {@code AmountOverflow}, the accumulation-point contract of ADR-0001)
     */
    public static void ensureZeroForClose(AccountBalance balance, AccountType type) {
        Objects.requireNonNull(balance, "balance");
        Objects.requireNonNull(type, "type");
        long natural = balance.natural(type);
        if (natural != 0) {
            throw new AccountBalanceNotZero(balance.accountId(), natural);
        }
    }
}
