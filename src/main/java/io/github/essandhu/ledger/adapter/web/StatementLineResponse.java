package io.github.essandhu.ledger.adapter.web;

import java.time.Instant;
import java.util.UUID;

import io.github.essandhu.ledger.domain.model.Posting;

/**
 * One statement line: a posting verbatim — raw signed amount in minor units (debit-positive,
 * The sign convention), the entry it belongs to for drill-down via {@code GET /journal-entries/{id}},
 * and the {@code (postedAt, id)} pair that IS its keyset position (which is why, unlike
 * {@link PostingResponse} inside an entry, a line carries its own {@code postedAt}).
 */
record StatementLineResponse(UUID id, UUID entryId, MoneyDto amount, Instant postedAt) {

    static StatementLineResponse from(Posting posting) {
        return new StatementLineResponse(posting.id().value(), posting.entryId().value(),
                MoneyDto.from(posting.amount()), posting.postedAt());
    }
}
