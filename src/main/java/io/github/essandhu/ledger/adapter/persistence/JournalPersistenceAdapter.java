package io.github.essandhu.ledger.adapter.persistence;

import java.util.Optional;

import org.springframework.stereotype.Component;

import io.github.essandhu.ledger.application.port.out.JournalRepository;
import io.github.essandhu.ledger.domain.model.EntryId;
import io.github.essandhu.ledger.domain.model.JournalEntry;

/**
 * JPA implementation of the {@link JournalRepository} port. Insert-only by construction: the
 * port exposes no update or delete, the entities are {@code @Immutable}, and the absent
 * UPDATE/DELETE grants would refuse anyway — I3's three layers, stacked. Transactions are
 * owned by the application services; repository calls join the surrounding transaction, which
 * is what makes the entry insert atomic with the snapshot bumps of
 * {@link BalancePersistenceAdapter} (ADR-0002).
 */
@Component
class JournalPersistenceAdapter implements JournalRepository {

    private final JournalEntryJpaRepository entries;
    private final PostingJpaRepository postings;

    JournalPersistenceAdapter(JournalEntryJpaRepository entries, PostingJpaRepository postings) {
        this.entries = entries;
        this.postings = postings;
    }

    @Override
    public void insert(JournalEntry entry) {
        // Header first — posting.entry_id references journal_entry(id), and Hibernate flushes
        // inserts in persist order. Inserting an existing id is a programming error per the
        // port contract; it surfaces as the primary-key violation at flush, loudly.
        entries.save(JournalEntryJpaEntity.fromDomain(entry));
        postings.saveAll(entry.postings().stream().map(PostingJpaEntity::fromDomain).toList());
    }

    @Override
    public Optional<JournalEntry> findById(EntryId id) {
        // Reassembles header + legs (posting_entry index); the id-ascending leg read
        // reproduces posting order — see PostingJpaRepository.findByEntryIdOrderByIdAsc.
        return entries.findById(id.value()).map(header -> header.toDomain(
                postings.findByEntryIdOrderByIdAsc(id.value()).stream()
                        .map(PostingJpaEntity::toDomain)
                        .toList()));
    }

    @Override
    public boolean reversalExistsFor(EntryId originalId) {
        return entries.existsByReversalOf(originalId.value());
    }
}
