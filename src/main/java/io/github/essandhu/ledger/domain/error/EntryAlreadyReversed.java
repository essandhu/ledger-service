package io.github.essandhu.ledger.domain.error;

import io.github.essandhu.ledger.domain.model.EntryId;

/**
 * I11: an entry can be reversed at most once ("reversed" is a derived property; the
 * original row never changes). Raised by the reversal use case inside the locked section, where
 * the shared account-set lock serializes double-reversal races (ADR-0003); the partial unique
 * index {@code journal_entry_reversed_once} is the at-rest backstop. Maps to 422 — the fix is
 * not a retry but a look at the reversal that already exists.
 */
public class EntryAlreadyReversed extends RuntimeException {

    private final EntryId originalId;

    public EntryAlreadyReversed(EntryId originalId) {
        super("entry %s is already reversed (I11: an entry can be reversed at most once)"
                .formatted(originalId.value()));
        this.originalId = originalId;
    }

    public EntryId originalId() {
        return originalId;
    }
}
