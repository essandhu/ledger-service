package io.github.essandhu.ledger.adapter.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;
import org.springframework.data.domain.Persistable;

import io.github.essandhu.ledger.domain.model.AccountId;
import io.github.essandhu.ledger.domain.model.CurrencyCode;
import io.github.essandhu.ledger.domain.model.EntryId;
import io.github.essandhu.ledger.domain.model.Money;
import io.github.essandhu.ledger.domain.model.Posting;
import io.github.essandhu.ledger.domain.model.PostingId;

/**
 * JPA face of one {@code posting} row — a leg of a posted entry, mapping the V3 columns
 * exactly: signed bigint minor units, debit-positive (ADR-0001), with the currency and
 * posted_at denormalized from account and header so M3 statements never join (PLAN §4.3).
 * {@code @Immutable} is layer 2 of I3: Hibernate never dirty-checks these rows, so even a bug
 * that mutates a loaded instance cannot emit an UPDATE (layer 1 is the mutator-free domain
 * record, layer 3 the absent UPDATE/DELETE grants that stop hand-written SQL too). No
 * equals/hashCode: entities are never held in sets or compared, and dead code is a coverage
 * and correctness liability.
 *
 * <p>{@link Persistable} for the same reason as {@code JournalEntryJpaEntity}: an append-only
 * table has no version column for Spring Data's isNew check, so the entity states the fact
 * directly — every instance {@link #fromDomain} builds is new, rows being written exactly
 * once. Without it, {@code save()} would merge: a probing SELECT per leg.
 */
@Entity
@Table(name = "posting")
@Immutable
class PostingJpaEntity implements Persistable<UUID> {

    @Id
    private UUID id;

    private UUID entryId;

    private UUID accountId;

    private long amount;

    private String currency;

    private Instant postedAt;

    protected PostingJpaEntity() {
        // JPA
    }

    static PostingJpaEntity fromDomain(Posting posting) {
        PostingJpaEntity entity = new PostingJpaEntity();
        entity.id = posting.id().value();
        entity.entryId = posting.entryId().value();
        entity.accountId = posting.accountId().value();
        entity.amount = posting.amount().amount();
        entity.currency = posting.amount().currency().value();
        entity.postedAt = posting.postedAt();
        return entity;
    }

    /**
     * Rebuilds through the domain constructors — {@code CurrencyCode} re-validates the code,
     * {@code Posting} re-rejects zero amounts (I2) — so database corruption surfaces loudly,
     * not as a quietly-invalid domain object.
     */
    Posting toDomain() {
        return new Posting(new PostingId(id), new EntryId(entryId), new AccountId(accountId),
                Money.of(amount, new CurrencyCode(currency)), postedAt);
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
