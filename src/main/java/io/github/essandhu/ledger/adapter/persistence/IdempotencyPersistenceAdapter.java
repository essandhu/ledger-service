package io.github.essandhu.ledger.adapter.persistence;

import java.time.Instant;
import java.util.Optional;

import org.springframework.stereotype.Component;

import io.github.essandhu.ledger.application.port.out.IdempotencyRecord;
import io.github.essandhu.ledger.application.port.out.IdempotencyRepository;

/**
 * {@link IdempotencyRepository} over JPA. Like every adapter, it never opens a transaction:
 * {@link #insert} joins the posting transaction the application service owns — which is the
 * entire ADR-0004 atomicity story (record + entry + balance bump, one commit) — and
 * {@link #deleteExpiredBatch} joins the purge service's per-batch transaction.
 */
@Component
class IdempotencyPersistenceAdapter implements IdempotencyRepository {

    private final IdempotencyRecordJpaRepository records;

    IdempotencyPersistenceAdapter(IdempotencyRecordJpaRepository records) {
        this.records = records;
    }

    @Override
    public Optional<IdempotencyRecord> find(String createdBy, String idempotencyKey) {
        return records.findById(new IdempotencyRecordJpaEntity.Key(createdBy, idempotencyKey))
                .map(IdempotencyRecordJpaEntity::toRecord);
    }

    @Override
    public void insert(IdempotencyRecord record) {
        // Persistable.isNew() = true skips the merge probe; a duplicate (created_by, idem_key)
        // surfaces as the PK violation at flush — the race arbiter of ADR-0004, resolved by
        // the web adapter's fresh-transaction retry, never swallowed here.
        records.save(IdempotencyRecordJpaEntity.fromRecord(record));
    }

    @Override
    public int deleteExpiredBatch(Instant cutoff, int batchSize) {
        return records.deleteExpiredBatch(cutoff, batchSize);
    }
}
