package io.github.essandhu.ledger.domain.error;

import io.github.essandhu.ledger.domain.model.AccountStatus;

/**
 * I12: a lifecycle edge that does not exist in PLAN §4.5 ({@code ACTIVE ⇄ FROZEN},
 * {@code ACTIVE|FROZEN → CLOSED}, CLOSED terminal). Same-state "transitions" never raise this —
 * they are declarative no-ops, not edges.
 */
public class InvalidStatusTransition extends RuntimeException {

    private final AccountStatus from;
    private final AccountStatus to;

    public InvalidStatusTransition(AccountStatus from, AccountStatus to) {
        super("no lifecycle edge %s -> %s (PLAN §4.5: CLOSED is terminal)".formatted(from, to));
        this.from = from;
        this.to = to;
    }

    public AccountStatus from() {
        return from;
    }

    public AccountStatus to() {
        return to;
    }
}
