package io.github.essandhu.ledger.adapter.web;

import java.util.UUID;

import io.github.essandhu.ledger.domain.model.Posting;

/**
 * One leg of an entry representation (PLAN §5), signed debit-positive minor units. No own
 * {@code postedAt}: it is the entry's, shared by every leg (PLAN §4.6) — repeating it per leg
 * would advertise a per-leg timestamp that cannot exist.
 */
record PostingResponse(UUID id, UUID accountId, MoneyDto amount) {

    static PostingResponse from(Posting posting) {
        return new PostingResponse(posting.id().value(), posting.accountId().value(),
                MoneyDto.from(posting.amount()));
    }
}
