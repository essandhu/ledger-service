package io.github.essandhu.ledger.support.fakes;

import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import io.github.essandhu.ledger.application.port.out.IdempotencyRecord;
import io.github.essandhu.ledger.application.port.out.IdempotencyRepository;

/**
 * Hand-written fake (TEST-STRATEGY §2.1): an in-memory idempotency store that ENFORCES the
 * port contract — duplicate (created_by, idem_key) insert throws like the PK would, the purge
 * deletes strictly-expired rows only, batch-bounded like the ctid DELETE. Counts inserts so
 * "a rejected posting records nothing" (ADR-0004) is a hard assertion.
 */
public final class FakeIdempotencyRepository implements IdempotencyRepository {

    private record Scope(String createdBy, String idempotencyKey) {
    }

    private final Map<Scope, IdempotencyRecord> rows = new LinkedHashMap<>();
    private int insertCalls;

    @Override
    public Optional<IdempotencyRecord> find(String createdBy, String idempotencyKey) {
        return Optional.ofNullable(rows.get(new Scope(createdBy, idempotencyKey)));
    }

    @Override
    public void insert(IdempotencyRecord record) {
        Scope scope = new Scope(record.createdBy(), record.idempotencyKey());
        if (rows.putIfAbsent(scope, record) != null) {
            throw new IllegalStateException("duplicate idempotency record for " + scope);
        }
        insertCalls++;
    }

    @Override
    public int deleteExpiredBatch(Instant cutoff, int batchSize) {
        int deleted = 0;
        Iterator<IdempotencyRecord> records = rows.values().iterator();
        while (records.hasNext() && deleted < batchSize) {
            if (records.next().expiresAt().isBefore(cutoff)) {
                records.remove();
                deleted++;
            }
        }
        return deleted;
    }

    /** Test-only seeding that bypasses the insert counter. */
    public void seed(IdempotencyRecord record) {
        rows.put(new Scope(record.createdBy(), record.idempotencyKey()), record);
    }

    public int insertCalls() {
        return insertCalls;
    }

    public int size() {
        return rows.size();
    }
}
