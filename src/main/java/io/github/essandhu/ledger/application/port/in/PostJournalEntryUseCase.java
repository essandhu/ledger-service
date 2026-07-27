package io.github.essandhu.ledger.application.port.in;

import java.util.List;
import java.util.Objects;

import io.github.essandhu.ledger.domain.model.EntryDraft;

/**
 * POST /journal-entries: record an arbitrary balanced 2..n-leg entry. The command
 * carries raw legs, not a validated draft — building the {@link EntryDraft} (and with it the
 * I1/I2 verdict) is the use case's first act, so validation order stays pinned in one place
 * and a garbage payload never touches the database (ADR-0003 step 1).
 *
 * <p>M4: every money-moving command carries the client's {@code Idempotency-Key} (ADR-0004).
 * The outcome is {@link PostingOutcome}: a fresh {@code Posted} entry, a {@code Replayed}
 * original response for a repeat of a recorded success, or the {@link IdempotencyKeyConflict}
 * rejection for key reuse with a different payload. The idempotency verdict is decided BEFORE
 * draft validation: a replay re-executes nothing, and a conflict outranks whatever else may be
 * wrong with the request (both are 422s; the conflict is the one that names the client bug).
 */
public interface PostJournalEntryUseCase {

    PostingOutcome postEntry(PostEntryCommand command);

    record PostEntryCommand(String description, List<EntryDraft.Leg> legs, String createdBy,
            String idempotencyKey) {
        public PostEntryCommand {
            Objects.requireNonNull(legs, "legs");
            legs = List.copyOf(legs); // defensive, immutable; rejects null elements
            Objects.requireNonNull(createdBy, "createdBy");
            idempotencyKey = InvalidIdempotencyKey.requireValid(idempotencyKey);
            // description and leg rules (I1, I2) are validated by the domain (EntryDraft) so
            // each rule lives in one place
        }
    }
}
