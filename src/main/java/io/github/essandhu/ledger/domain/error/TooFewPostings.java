package io.github.essandhu.ledger.domain.error;

/**
 * I2 violated: double-entry has two sides, so an entry needs at least two postings (the domain model:
 * a header plus 2..n legs) — a single leg would create or destroy value, zero legs would record
 * nothing. Rejected at draft construction, before any I/O. Maps to 422: the payload is
 * well-formed JSON, the ledger refuses the semantics. There is no schema mirror — a header-only
 * entry is representable at rest — so this rule lives here and under reconciliation's eye (I15).
 */
public class TooFewPostings extends RuntimeException {

    private final int count;

    public TooFewPostings(int count) {
        super("an entry needs at least 2 postings, got %d (I2: double-entry has two sides)"
                .formatted(count));
        this.count = count;
    }

    public int count() {
        return count;
    }
}
