package io.github.essandhu.ledger.adapter.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.github.essandhu.ledger.domain.model.EntryType;
import io.github.essandhu.ledger.domain.model.JournalEntry;

/**
 * Journal-entry representation (PLAN §5), shared by all three creating endpoints and the GET —
 * a posted fact is one shape however it came to exist. {@code reversalOf} is null except on
 * REVERSAL entries (I11); {@code createdBy} is the JWT subject that posted it (PLAN §7); legs
 * keep their submitted order (I11's exactness proof compares positionally).
 */
record EntryResponse(
        UUID id,
        EntryType entryType,
        String description,
        UUID reversalOf,
        String createdBy,
        Instant postedAt,
        List<PostingResponse> postings) {

    static EntryResponse from(JournalEntry entry) {
        return new EntryResponse(entry.id().value(), entry.entryType(), entry.description(),
                entry.reversalOf() == null ? null : entry.reversalOf().value(),
                entry.createdBy(), entry.postedAt(),
                entry.postings().stream().map(PostingResponse::from).toList());
    }
}
