package io.github.essandhu.ledger.application.port.in;

import io.github.essandhu.ledger.domain.model.EntryId;
import io.github.essandhu.ledger.domain.model.JournalEntry;

/** GET /journal-entries/{id} (PLAN §5). */
public interface GetJournalEntryQuery {

    /** @throws EntryNotFound if no such entry exists */
    JournalEntry byId(EntryId id);
}
