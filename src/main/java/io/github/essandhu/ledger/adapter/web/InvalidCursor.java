package io.github.essandhu.ledger.adapter.web;

/**
 * 400-family shape violation: the {@code cursor} query parameter is not a token this service
 * issued for this account's statement. Mapped to a bare {@code about:blank} problem like every
 * other 400 — typed ProblemTypes slugs are the business-rule vocabulary (422/409), and a
 * garbage cursor, like any malformed parameter, can never become a valid request.
 */
class InvalidCursor extends RuntimeException {

    InvalidCursor(String message) {
        super(message);
    }
}
