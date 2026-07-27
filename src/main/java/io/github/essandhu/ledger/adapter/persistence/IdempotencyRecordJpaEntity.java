package io.github.essandhu.ledger.adapter.persistence;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;
import org.springframework.data.domain.Persistable;

import io.github.essandhu.ledger.application.port.out.IdempotencyRecord;
import io.github.essandhu.ledger.domain.model.EntryId;

/**
 * JPA face of the {@code idempotency_record} row (V4), mapping ADR-0004's bookkeeping exactly.
 * {@code @Immutable} for the same reason as the journal entities: rows are written exactly
 * once (the same-transaction record write), and Hibernate must never dirty-check one back.
 * The purge path is a set-based native DELETE on the repository — no entity mutation, no
 * removal by managed instance. No equals/hashCode: entities are never held in sets or
 * compared, and dead code is a coverage and correctness liability.
 *
 * <p>First composite key in the schema: the (created_by, idem_key) scope IS the primary key,
 * expressed as a record {@code @IdClass}. {@link Persistable} states isNew()=true
 * for the same append-only reason as {@code JournalEntryJpaEntity}: every instance
 * {@link #fromRecord} builds is new, and without the declaration save() would merge-probe the
 * hottest write path.
 *
 * <p>{@code responseBody} maps the V4 {@code text} column (deliberately not jsonb — jsonb
 * re-serialization normalizes key order and would break the byte-identical replay ADR-0004
 * promises; the IS JSON CHECK keeps validity enforcement at rest).
 */
@Entity
@Table(name = "idempotency_record")
@Immutable
@IdClass(IdempotencyRecordJpaEntity.Key.class)
class IdempotencyRecordJpaEntity implements Persistable<IdempotencyRecordJpaEntity.Key> {

    /** The (created_by, idem_key) scope as an id class — a record, so equals/hashCode/
     * serialization come from the language, not hand-rolled entity code. */
    record Key(String createdBy, String idemKey) implements Serializable {
    }

    @Id
    private String createdBy;

    @Id
    private String idemKey;

    private String requestHash;

    private UUID entryId;

    private int responseStatus;

    private String responseBody;

    private Instant createdAt;

    private Instant expiresAt;

    protected IdempotencyRecordJpaEntity() {
        // JPA
    }

    static IdempotencyRecordJpaEntity fromRecord(IdempotencyRecord record) {
        IdempotencyRecordJpaEntity entity = new IdempotencyRecordJpaEntity();
        entity.createdBy = record.createdBy();
        entity.idemKey = record.idempotencyKey();
        entity.requestHash = record.requestHash();
        entity.entryId = record.entryId().value();
        entity.responseStatus = record.responseStatus();
        entity.responseBody = record.responseBody();
        entity.createdAt = record.createdAt();
        entity.expiresAt = record.expiresAt();
        return entity;
    }

    /** Rebuilds through the port record's constructor so its null guards re-run on every load
     * — database corruption surfaces loudly, the {@code toDomain} convention. */
    IdempotencyRecord toRecord() {
        return new IdempotencyRecord(createdBy, idemKey, requestHash, new EntryId(entryId),
                responseStatus, responseBody, createdAt, expiresAt);
    }

    @Override
    public Key getId() {
        return new Key(createdBy, idemKey);
    }

    @Override
    public boolean isNew() {
        // True by construction: only fromRecord instances are ever saved; loaded instances
        // are read-only faces of write-once rows that no code path saves back.
        return true;
    }
}
