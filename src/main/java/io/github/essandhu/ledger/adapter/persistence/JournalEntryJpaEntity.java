package io.github.essandhu.ledger.adapter.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;
import org.springframework.data.domain.Persistable;

import io.github.essandhu.ledger.domain.model.EntryId;
import io.github.essandhu.ledger.domain.model.EntryType;
import io.github.essandhu.ledger.domain.model.JournalEntry;
import io.github.essandhu.ledger.domain.model.Posting;

/**
 * JPA face of the {@code journal_entry} header row, mapping the V3 columns exactly.
 * {@code @Immutable} is layer 2 of I3: Hibernate never dirty-checks these rows, so even a bug
 * that mutates a loaded instance cannot emit an UPDATE (layer 1 is the mutator-free domain
 * record, layer 3 the absent UPDATE/DELETE grants that stop hand-written SQL too). No
 * equals/hashCode: entities are never held in sets or compared, and dead code is a coverage
 * and correctness liability.
 *
 * <p>{@link Persistable} answers Spring Data's isNew question the way {@code AccountJpaEntity}
 * answers it with its null-{@code @Version} sentinel — but an append-only table deliberately
 * carries no version column (a mutability column would advertise an edit path that must not
 * exist, V3), so the entity states the fact directly: every instance {@link #fromDomain}
 * builds is new, because rows are written exactly once. Without it, {@code save()} would fall
 * back to merge — a probing SELECT per insert on the hottest write path.
 */
@Entity
@Table(name = "journal_entry")
@Immutable
class JournalEntryJpaEntity implements Persistable<UUID> {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    private EntryType entryType;

    private String description;

    private UUID reversalOf;

    /** The client's Idempotency-Key (M4, ADR-0004): with createdBy, one entry per (principal,
     * key) forever via the V3 backstop index. NULL on pre-M4 rows. */
    private String idempotencyKey;

    private String createdBy;

    private Instant postedAt;

    protected JournalEntryJpaEntity() {
        // JPA
    }

    static JournalEntryJpaEntity fromDomain(JournalEntry entry) {
        JournalEntryJpaEntity entity = new JournalEntryJpaEntity();
        entity.id = entry.id().value();
        entity.entryType = entry.entryType();
        entity.description = entry.description();
        entity.reversalOf = entry.reversalOf() == null ? null : entry.reversalOf().value();
        entity.idempotencyKey = entry.idempotencyKey();
        entity.createdBy = entry.createdBy();
        entity.postedAt = entry.postedAt();
        return entity;
    }

    /**
     * Rebuilds through the domain constructor with the postings the adapter reassembled, so
     * the referential invariants (reversal shape, posting ownership — I11) re-run on every
     * load: database corruption surfaces loudly, not as a quietly-invalid domain object.
     */
    JournalEntry toDomain(List<Posting> postings) {
        return new JournalEntry(new EntryId(id), entryType, description,
                reversalOf == null ? null : new EntryId(reversalOf), createdBy, idempotencyKey,
                postedAt, postings);
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        // True by construction: only fromDomain instances are ever saved; loaded instances
        // are read-only faces of immutable rows that no code path saves back.
        return true;
    }
}
