package io.github.essandhu.ledger.application.service;

import java.time.Clock;

import org.springframework.transaction.annotation.Transactional;

import io.github.essandhu.ledger.application.port.out.IdempotencyRepository;

/**
 * The ADR-0004 retention path, DESIGNED BUT SHIPPED DISABLED
 * ({@code ledger.idempotency.purge.enabled=false}): batched deletes of expired idempotency
 * records. One transaction per batch — the whole point of batching is that the money path
 * never queues behind a bulk delete, so the loop lives with the scheduler (config) and each
 * call here is its own short transaction. Safe by construction to run at any time: purging a
 * record degrades only replay/conflict DIAGNOSTICS for that key — V3's permanent backstop
 * index on journal_entry makes the double-post impossible forever (ADR-0004, option 3b).
 *
 * <p>No {@code @PreAuthorize}: this is operator machinery driven by the scheduler, not a
 * caller-facing use case — there is no principal to authorize.
 */
public class IdempotencyPurgeService {

    private final IdempotencyRepository idempotency;
    private final Clock clock;

    public IdempotencyPurgeService(IdempotencyRepository idempotency, Clock clock) {
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /** Deletes one batch of records expired as of the injected Clock; returns how many went
     * (fewer than {@code batchSize} means the backlog is drained). */
    @Transactional
    public int purgeExpiredBatch(int batchSize) {
        return idempotency.deleteExpiredBatch(clock.instant(), batchSize);
    }
}
