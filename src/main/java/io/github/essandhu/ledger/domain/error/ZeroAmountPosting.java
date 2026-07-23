package io.github.essandhu.ledger.domain.error;

import io.github.essandhu.ledger.domain.model.AccountId;

/**
 * I2 violated: a zero-amount leg moves no value and would be pure noise in every statement, so
 * the draft rejects it before any I/O; the {@code posting_amount_nonzero} CHECK mirrors the
 * rule at rest. Maps to 422. Carries the offending account so multi-leg payloads get a precise
 * finger pointed, not a vague complaint.
 */
public class ZeroAmountPosting extends RuntimeException {

    private final AccountId accountId;

    public ZeroAmountPosting(AccountId accountId) {
        super("posting on account %s has a zero amount (I2: every leg moves value)"
                .formatted(accountId.value()));
        this.accountId = accountId;
    }

    public AccountId accountId() {
        return accountId;
    }
}
