package io.github.essandhu.ledger.adapter.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import io.github.essandhu.ledger.application.port.in.StatementCursor;
import io.github.essandhu.ledger.application.port.in.StatementFilter;
import io.github.essandhu.ledger.application.port.out.JournalRepository;
import io.github.essandhu.ledger.domain.model.AccountId;
import io.github.essandhu.ledger.domain.model.EntryId;
import io.github.essandhu.ledger.domain.model.JournalEntry;
import io.github.essandhu.ledger.domain.model.Posting;

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
    public Optional<JournalEntry> findByCreatorAndKey(String createdBy, String idempotencyKey) {
        // Served by V3's partial unique backstop index (created_by, idempotency_key) — at
        // most one row; same reassembly as findById.
        return entries.findByCreatedByAndIdempotencyKey(createdBy, idempotencyKey)
                .map(header -> header.toDomain(
                        postings.findByEntryIdOrderByIdAsc(header.getId()).stream()
                                .map(PostingJpaEntity::toDomain)
                                .toList()));
    }

    @Override
    public boolean reversalExistsFor(EntryId originalId) {
        return entries.existsByReversalOf(originalId.value());
    }

    @Override
    public PostingAggregate sumPostingsAsOf(AccountId accountId, Instant at) {
        PostingJpaRepository.AsOfAggregateRow row =
                postings.sumPostingsAsOf(accountId.value(), at);
        return new PostingAggregate(row.getSum(), row.getCount());
    }

    @Override
    public List<Posting> statementLines(AccountId accountId, StatementFilter filter,
            Optional<StatementCursor> after, int limit) {
        // Two queries on the cursor axis (present/absent — the row-value predicate cannot be
        // COALESCEd away); the window bounds stay nullable params widened in the SQL itself.
        Instant from = filter.fromExclusive().orElse(null);
        Instant to = filter.toInclusive().orElse(null);
        List<PostingJpaEntity> rows = after
                .map(cursor -> postings.statementAfterCursor(accountId.value(), from, to,
                        cursor.postedAt(), cursor.id().value(), limit))
                .orElseGet(() -> postings.statementFirstPage(accountId.value(), from, to, limit));
        return rows.stream().map(PostingJpaEntity::toDomain).toList();
    }
}
