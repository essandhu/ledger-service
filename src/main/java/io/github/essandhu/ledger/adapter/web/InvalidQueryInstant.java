package io.github.essandhu.ledger.adapter.web;

import java.time.Instant;

/**
 * 400-family shape violation: an instant query parameter parses in java.time but lies outside
 * the range the database can bind ({@link WireInstants}). Bare {@code about:blank} problem
 * like every other 400 — the request can never become valid.
 */
class InvalidQueryInstant extends RuntimeException {

    InvalidQueryInstant(String parameter, Instant value) {
        super("query parameter '%s' is outside the supported instant range [%s, %s]: %s"
                .formatted(parameter, WireInstants.MIN, WireInstants.MAX, value));
    }
}
