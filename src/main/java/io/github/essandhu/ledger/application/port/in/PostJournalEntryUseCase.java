package io.github.essandhu.ledger.application.port.in;

import java.util.List;
import java.util.Objects;

import io.github.essandhu.ledger.domain.model.EntryDraft;
import io.github.essandhu.ledger.domain.model.JournalEntry;

/**
 * POST /journal-entries (PLAN §5): record an arbitrary balanced 2..n-leg entry. The command
 * carries raw legs, not a validated draft — building the {@link EntryDraft} (and with it the
 * I1/I2 verdict) is the use case's first act, so validation order stays pinned in one place
 * and a garbage payload never touches the database (ADR-0003 step 1).
 */
public interface PostJournalEntryUseCase {

    JournalEntry postEntry(PostEntryCommand command);

    record PostEntryCommand(String description, List<EntryDraft.Leg> legs, String createdBy) {
        public PostEntryCommand {
            Objects.requireNonNull(legs, "legs");
            legs = List.copyOf(legs); // defensive, immutable; rejects null elements
            Objects.requireNonNull(createdBy, "createdBy");
            // description and leg rules (I1, I2) are validated by the domain (EntryDraft) so
            // each rule lives in one place
        }
    }
}
