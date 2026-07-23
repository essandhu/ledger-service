package io.github.essandhu.ledger.domain.error;

import io.github.essandhu.ledger.domain.model.AccountId;

/**
 * I6: an account with {@code allow_negative = false} may never carry a negative NATURAL balance
 * (PLAN §4.2 — the check reads {@code raw × direction}, so a credit-normal account overdraws
 * upward in raw terms). Evaluated by the posting use case under the ordered account_balance
 * lock (ADR-0003), against the checked would-be balance; rejected requests fail with this
 * domain error, never with a lock or serialization error. Maps to 422. Carries the account and
 * the natural balance the posting would have produced.
 */
public class OverdraftViolation extends RuntimeException {

    private final AccountId accountId;
    private final long attemptedNaturalBalance;

    public OverdraftViolation(AccountId accountId, long attemptedNaturalBalance) {
        super("posting would overdraw account %s to natural balance %d (I6: allow_negative is false)"
                .formatted(accountId.value(), attemptedNaturalBalance));
        this.accountId = accountId;
        this.attemptedNaturalBalance = attemptedNaturalBalance;
    }

    public AccountId accountId() {
        return accountId;
    }

    public long attemptedNaturalBalance() {
        return attemptedNaturalBalance;
    }
}
