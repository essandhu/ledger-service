package io.github.essandhu.ledger.adapter.web;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/**
 * POST /journal-entries body (PLAN §5): an arbitrary balanced 2..n-leg entry. Deliberately
 * {@code @NotEmpty}, not min-two: an absent or empty leg list is a shape defect (400), but a
 * single-leg payload is well-formed JSON the LEDGER refuses — that verdict belongs to the
 * domain's I2 rule and its 422 {@code too-few-postings}, not to bean validation. The
 * description is optional (absence means absence); its finer text rules (blank, control
 * characters) are the domain's 400 backstop.
 */
record PostJournalEntryRequest(
        @Size(max = 500) String description,
        @NotEmpty List<@Valid PostingLegRequest> postings) {
}
