package io.github.essandhu.ledger.application.port.in;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Optional posted_at window for a statement, pinned at M3 as {@code (from, to]} — from
 * EXCLUSIVE, to INCLUSIVE — so a statement composes with I10's algebra exactly:
 * {@code asOf(from) + Σ statement(from, to] = asOf(to)}. {@code from} is the as-of instant the
 * walk starts from (the opening-balance boundary), like a paper statement's "balance as of";
 * {@code to} is the closing boundary. An empty window ({@code from >= to}) is a valid request
 * with an empty answer, not an error.
 */
public record StatementFilter(Optional<Instant> fromExclusive, Optional<Instant> toInclusive) {

    public StatementFilter {
        Objects.requireNonNull(fromExclusive, "fromExclusive");
        Objects.requireNonNull(toInclusive, "toInclusive");
    }

    public static StatementFilter unbounded() {
        return new StatementFilter(Optional.empty(), Optional.empty());
    }
}
