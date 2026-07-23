package io.github.essandhu.ledger.support.fakes;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import io.github.essandhu.ledger.application.port.out.JournalRepository;
import io.github.essandhu.ledger.domain.model.EntryId;
import io.github.essandhu.ledger.domain.model.EntryType;
import io.github.essandhu.ledger.domain.model.JournalEntry;

/**
 * Hand-written fake (TEST-STRATEGY §2.1: fakes over mock-framework stubs, so the port contract
 * is enforced, not just echoed): a real in-memory journal with the same observable semantics as
 * the JPA adapter — insert-only (I3 has no update path to fake), duplicate-id failure, and
 * reversal existence derived by scanning for a REVERSAL pointing at the original, exactly as
 * the adapter's query does. Counts inserts so "a rejected posting writes NOTHING" (ADR-0004)
 * is assertable.
 */
public final class FakeJournalRepository implements JournalRepository {

    private final Map<EntryId, JournalEntry> rows = new LinkedHashMap<>();
    private int insertCalls;

    @Override
    public void insert(JournalEntry entry) {
        if (rows.putIfAbsent(entry.id(), entry) != null) {
            throw new IllegalStateException("duplicate insert for entry " + entry.id());
        }
        insertCalls++;
    }

    @Override
    public Optional<JournalEntry> findById(EntryId id) {
        return Optional.ofNullable(rows.get(id));
    }

    @Override
    public boolean reversalExistsFor(EntryId originalId) {
        return rows.values().stream()
                .anyMatch(entry -> entry.entryType() == EntryType.REVERSAL
                        && originalId.equals(entry.reversalOf()));
    }

    /** Test-only seeding that bypasses the use-case layer (and the insert counter). */
    public void seed(JournalEntry entry) {
        rows.put(entry.id(), entry);
    }

    public int insertCalls() {
        return insertCalls;
    }
}
