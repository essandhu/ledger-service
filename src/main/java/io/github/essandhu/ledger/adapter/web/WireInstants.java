package io.github.essandhu.ledger.adapter.web;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Instants crossing the API's wire ({@code ?at=}, {@code ?from=}, {@code ?to=}), tamed for the
 * database: bounded to a range {@code timestamptz} can bind (java.time reaches ±1e9 years;
 * PostgreSQL does not — an unguarded bind would surface as a 500, and a value the query cannot
 * even bind can never be valid, which is the 400 family's definition), and truncated to the
 * ledger's microsecond grid. Truncation is semantically EXACT, not lossy: every {@code
 * posted_at} lies on the grid, so {@code posted_at <= at ⟺ posted_at <= floor_µs(at)} and
 * {@code posted_at > from ⟺ posted_at > floor_µs(from)} — flooring the bound evaluates the
 * mathematical predicate while removing the driver's sub-microsecond rounding behavior from
 * the contract entirely.
 */
final class WireInstants {

    /** Wire bounds, comfortably inside timestamptz's coverage (4713 BC .. 294276 AD) while
     * keeping every accepted value four-digit-year printable. */
    static final Instant MIN = Instant.parse("0001-01-01T00:00:00Z");
    static final Instant MAX = Instant.parse("9999-12-31T23:59:59.999999Z");

    private WireInstants() {
    }

    /** @throws InvalidQueryInstant if {@code value} lies outside [{@link #MIN}, {@link #MAX}] */
    static Instant normalize(String parameter, Instant value) {
        if (value.isBefore(MIN) || value.isAfter(MAX)) {
            throw new InvalidQueryInstant(parameter, value);
        }
        return value.truncatedTo(ChronoUnit.MICROS);
    }
}
