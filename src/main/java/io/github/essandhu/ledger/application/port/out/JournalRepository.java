package io.github.essandhu.ledger.application.port.out;

import java.util.Optional;

import io.github.essandhu.ledger.domain.model.EntryId;
import io.github.essandhu.ledger.domain.model.JournalEntry;

/**
 * Driven port for journal persistence; implemented by the JPA adapter. Deliberately has no
 * update or delete operation — insert-only is layer 1 of I3 at the port boundary (layer 2 is
 * the {@code @Immutable} mapping, layer 3 the absent UPDATE/DELETE grants on
 * {@code journal_entry} and {@code posting}).
 */
public interface JournalRepository {

    /**
     * Persists a new entry header together with all its postings. Inserting an existing id is
     * a programming error.
     */
    void insert(JournalEntry entry);

    Optional<JournalEntry> findById(EntryId id);

    /**
     * Whether a REVERSAL pointing at {@code originalId} already exists (I11: at most once).
     * The reversal use case evaluates this INSIDE the locked section — the shared account-set
     * lock serializes double-reversal races (ADR-0003), and the partial unique index
     * {@code journal_entry_reversed_once} is the at-rest backstop.
     */
    boolean reversalExistsFor(EntryId originalId);
}
