package io.github.essandhu.ledger.domain.error;

import io.github.essandhu.ledger.domain.model.CurrencyCode;

/**
 * Money never crosses currencies implicitly (ADR-0001). Thrown by {@code Money.plus} when the
 * operands disagree, and by the posting use case when a leg's currency disagrees with its
 * account's (PLAN §4.2: an account holds exactly one currency). Maps to 422: the request is
 * well-formed, the ledger just refuses the semantics.
 */
public class CurrencyMismatch extends RuntimeException {

    private final CurrencyCode expected;
    private final CurrencyCode actual;

    public CurrencyMismatch(CurrencyCode expected, CurrencyCode actual) {
        super("currency mismatch: expected %s, got %s (money never crosses currencies implicitly)"
                .formatted(expected.value(), actual.value()));
        this.expected = expected;
        this.actual = actual;
    }

    public CurrencyCode expected() {
        return expected;
    }

    public CurrencyCode actual() {
        return actual;
    }
}
