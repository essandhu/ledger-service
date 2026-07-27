package io.github.essandhu.ledger.domain.error;

/**
 * A posting attempted against a FROZEN account. Freezing blocks postings in both directions
 * but not metadata or queries, and — unlike {@link AccountClosed} closing — it is reversible.
 * Same 422 rejection family as the other posting refusals; a distinct type (and
 * problem slug) because "temporarily suspended, try after unfreeze" and "terminally closed"
 * are operationally different answers.
 */
public class AccountFrozen extends RuntimeException {

    public AccountFrozen(String message) {
        super(message);
    }
}
