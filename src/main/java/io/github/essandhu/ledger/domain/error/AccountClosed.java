package io.github.essandhu.ledger.domain.error;

/**
 * An operation attempted against a CLOSED account. Closing is terminal (PLAN §4.5): a closed
 * account rejects metadata edits now (M1) and postings from M2 on — PLAN §5 files both under
 * the same 422 rejection family.
 */
public class AccountClosed extends RuntimeException {

    public AccountClosed(String message) {
        super(message);
    }
}
