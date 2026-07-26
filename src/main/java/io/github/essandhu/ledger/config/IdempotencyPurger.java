package io.github.essandhu.ledger.config;

import org.springframework.scheduling.annotation.Scheduled;

import io.github.essandhu.ledger.application.service.IdempotencyPurgeService;

/**
 * The scheduled driver of ADR-0004's batched purge: keeps asking the application service for
 * one-transaction batches until a short batch says the backlog is drained. Lives in config
 * like the metrics decorator — scheduling is infrastructure, and the application core's
 * Spring surface stays frozen at {@code @Transactional}/{@code @PreAuthorize} (I14). Only
 * instantiated when the purge is enabled ({@code IdempotencyPurgeConfig}).
 */
final class IdempotencyPurger {

    /** ADR-0004's recorded batch shape: 1000 rows per transaction, looped. */
    static final int BATCH_SIZE = 1000;

    private final IdempotencyPurgeService purge;

    IdempotencyPurger(IdempotencyPurgeService purge) {
        this.purge = purge;
    }

    /** One purge sweep; returns the total deleted (the scheduler ignores it, tests assert it). */
    @Scheduled(fixedDelayString = "${ledger.idempotency.purge.interval:PT1H}")
    public int purgeExpired() {
        int total = 0;
        int deleted;
        do {
            deleted = purge.purgeExpiredBatch(BATCH_SIZE);
            total += deleted;
        } while (deleted == BATCH_SIZE);
        return total;
    }
}
