package io.github.essandhu.ledger.application.port.in;

import java.util.Objects;

import io.github.essandhu.ledger.domain.model.EntryId;

/**
 * POST /journal-entries/{id}/reversal: post the entry whose legs exactly negate
 * {@code originalId}'s (I11). A reversal is a new entry through the full posting protocol —
 * draft validation, ordered locks, status and overdraft checks (which is why reversing into a
 * FROZEN account fails, the documented operational caveat of the lifecycle rules) — plus the
 * at-most-once check evaluated inside the locked section ({@code EntryAlreadyReversed}).
 *
 * <p>M4: carries the {@code Idempotency-Key} and returns {@link PostingOutcome} (ADR-0004) —
 * see {@link PostJournalEntryUseCase} for the shared semantics. The idempotency verdict comes
 * BEFORE the original-entry lookup: replaying a recorded success answers from the record
 * without probing anything, so only a first attempt can 404.
 *
 * @throws EntryNotFound if {@code originalId} resolves to nothing — it is a path id, so the
 *         miss is a 404, unlike the 422 of a payload-referenced unknown account
 */
public interface ReverseEntryUseCase {

    PostingOutcome reverse(ReverseCommand command);

    record ReverseCommand(EntryId originalId, String description, String createdBy,
            String idempotencyKey) {
        public ReverseCommand {
            Objects.requireNonNull(originalId, "originalId");
            Objects.requireNonNull(createdBy, "createdBy");
            idempotencyKey = InvalidIdempotencyKey.requireValid(idempotencyKey);
            // description is the reversal's own, nullable like any other; validated by the domain
        }
    }
}
