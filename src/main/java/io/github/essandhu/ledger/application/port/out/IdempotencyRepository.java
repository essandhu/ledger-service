package io.github.essandhu.ledger.application.port.out;

import java.time.Instant;
import java.util.Optional;

/**
 * The idempotency bookkeeping store (ADR-0004): lookup by scope, insert-in-posting-transaction,
 * and the batched expiry delete for the designed-but-disabled purge. No update — records are
 * written exactly once. The adapter joins the caller's transaction (it never opens its own),
 * which is what makes record + entry + balance bump one atomic truth.
 */
public interface IdempotencyRepository {

    Optional<IdempotencyRecord> find(String createdBy, String idempotencyKey);

    void insert(IdempotencyRecord record);

    /**
     * Deletes at most {@code batchSize} records with {@code expires_at < cutoff} (the ADR-0004
     * purge shape: small batches so the money path never queues behind a bulk delete), returning
     * how many went. Callers own the transaction-per-batch loop.
     */
    int deleteExpiredBatch(Instant cutoff, int batchSize);
}
