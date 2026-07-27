package io.github.essandhu.ledger.domain.error;

/**
 * An operation attempted against a CLOSED account. Closing is terminal: a closed
 * account rejects metadata edits now (M1) and postings from M2 on — the API contract files both under
 * the same 422 rejection family.
 */
public class AccountClosed extends RuntimeException {

    public AccountClosed(String message) {
        super(message);
    }
}
