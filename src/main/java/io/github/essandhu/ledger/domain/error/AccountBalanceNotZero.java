package io.github.essandhu.ledger.domain.error;

import io.github.essandhu.ledger.domain.model.AccountId;

/**
 * I12, posting half of closing: an account may close only at natural balance zero (the lifecycle rules —
 * closing an account still carrying value would strand it). The rule reads the NATURAL balance,
 * {@code raw × direction}, so a credit-normal account with negative raw is equally unclosable.
 * Evaluated by the close use case while holding the account_balance lock (ADR-0003: lifecycle
 * and posting serialize on the same lock, so no post slips between check and close). Maps to
 * 422. Carries the account and the residual natural balance the caller must clear first.
 */
public class AccountBalanceNotZero extends RuntimeException {

    private final AccountId accountId;
    private final long naturalBalance;

    public AccountBalanceNotZero(AccountId accountId, long naturalBalance) {
        super("account %s cannot close carrying natural balance %d (close requires zero)"
                .formatted(accountId.value(), naturalBalance));
        this.accountId = accountId;
        this.naturalBalance = naturalBalance;
    }

    public AccountId accountId() {
        return accountId;
    }

    public long naturalBalance() {
        return naturalBalance;
    }
}
