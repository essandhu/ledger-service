package io.github.essandhu.ledger.application.port.in;

import io.github.essandhu.ledger.domain.model.AccountId;

/**
 * A path-addressed account that does not exist — part of the use-case contract, not a domain
 * rule violation, hence this package and its 404 mapping. M2's "unknown account referenced in a
 * posting payload" is a different error with different semantics (422 per the API contract); do not fold
 * the two together.
 */
public class AccountNotFound extends RuntimeException {

    public AccountNotFound(AccountId id) {
        super("no account " + id.value());
    }
}
