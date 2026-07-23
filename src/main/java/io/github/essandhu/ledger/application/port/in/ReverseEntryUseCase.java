package io.github.essandhu.ledger.application.port.in;

import java.util.Objects;

import io.github.essandhu.ledger.domain.model.EntryId;
import io.github.essandhu.ledger.domain.model.JournalEntry;

/**
 * POST /journal-entries/{id}/reversal (PLAN §5): post the entry whose legs exactly negate
 * {@code originalId}'s (I11). A reversal is a new entry through the full posting protocol —
 * draft validation, ordered locks, status and overdraft checks (which is why reversing into a
 * FROZEN account fails, the documented operational caveat of PLAN §4.5) — plus the
 * at-most-once check evaluated inside the locked section ({@code EntryAlreadyReversed}).
 *
 * @throws EntryNotFound if {@code originalId} resolves to nothing — it is a path id, so the
 *         miss is a 404, unlike the 422 of a payload-referenced unknown account
 */
public interface ReverseEntryUseCase {

    JournalEntry reverse(ReverseCommand command);

    record ReverseCommand(EntryId originalId, String description, String createdBy) {
        public ReverseCommand {
            Objects.requireNonNull(originalId, "originalId");
            Objects.requireNonNull(createdBy, "createdBy");
            // description is the reversal's own, nullable like any other; validated by the domain
        }
    }
}
