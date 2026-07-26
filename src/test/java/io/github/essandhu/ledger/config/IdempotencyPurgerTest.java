package io.github.essandhu.ledger.config;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.essandhu.ledger.application.port.out.IdempotencyRecord;
import io.github.essandhu.ledger.application.service.IdempotencyPurgeService;
import io.github.essandhu.ledger.domain.model.EntryId;
import io.github.essandhu.ledger.support.fakes.FakeIdempotencyRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The purge sweep's drain loop (ADR-0004): keep taking full batches until a short batch says
 * the backlog is empty, delete strictly-expired records only. The real ctid DELETE against
 * PostgreSQL is {@link IdempotencyPurgeIntegrationTest}'s job; here the fake enforces the
 * batch-bounded port contract.
 */
@DisplayName("ADR-0004: the purge sweep drains expired records in bounded batches")
class IdempotencyPurgerTest {

    private static final Instant NOW = Instant.parse("2026-07-23T10:00:00Z");

    private final FakeIdempotencyRepository store = new FakeIdempotencyRepository();
    private final IdempotencyPurger purger = new IdempotencyPurger(
            new IdempotencyPurgeService(store, Clock.fixed(NOW, ZoneOffset.UTC)));

    private void seed(String key, Instant expiresAt) {
        store.seed(new IdempotencyRecord("purge-tester", key, "a".repeat(64),
                new EntryId(java.util.UUID.randomUUID()), 201, "{}",
                expiresAt.minusSeconds(90L * 24 * 3600), expiresAt));
    }

    @Test
    @DisplayName("one sweep drains a backlog larger than a batch, and spares the unexpired")
    void sweep_drains_multi_batch_backlog_and_spares_live_records() {
        int expired = IdempotencyPurger.BATCH_SIZE * 2 + 500;
        for (int i = 0; i < expired; i++) {
            seed("expired-" + i, NOW.minusSeconds(1));
        }
        seed("live-boundary", NOW);           // expires_at = now is NOT yet expired (strict <)
        seed("live-future", NOW.plusSeconds(3600));

        assertThat(purger.purgeExpired()).isEqualTo(expired);
        assertThat(store.size()).as("unexpired records survive").isEqualTo(2);
        assertThat(store.find("purge-tester", "live-boundary")).isPresent();
        assertThat(store.find("purge-tester", "live-future")).isPresent();
    }

    @Test
    @DisplayName("an empty backlog is one short batch — the sweep stops immediately")
    void empty_backlog_sweeps_zero() {
        seed("live", NOW.plusSeconds(1));
        assertThat(purger.purgeExpired()).isZero();
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("'enabling is a configuration change' is REAL: the exact property flips the scheduler bean into existence")
    void enabling_property_creates_the_scheduler_and_its_schedule_resolves() {
        // The disabled default is proven against the full context (IdempotencyPurgeIntegrationTest);
        // this proves the ENABLED half — @ConditionalOnProperty's property name, the bean
        // wiring, and (via context refresh) the @Scheduled placeholder's default — so a typo
        // in any of them fails here instead of silently no-opping in production.
        org.springframework.boot.test.context.runner.ApplicationContextRunner runner =
                new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                        .withBean(IdempotencyPurgeService.class,
                                () -> new IdempotencyPurgeService(store,
                                        Clock.fixed(NOW, ZoneOffset.UTC)))
                        .withUserConfiguration(IdempotencyPurgeConfig.class);

        runner.withPropertyValues("ledger.idempotency.purge.enabled=true")
                .run(context -> assertThat(context.getBeanNamesForType(IdempotencyPurger.class))
                        .hasSize(1));
        runner.run(context -> assertThat(context.getBeanNamesForType(IdempotencyPurger.class))
                .as("no property, no purge machinery")
                .isEmpty());
        runner.withPropertyValues("ledger.idempotency.purge.enabled=false")
                .run(context -> assertThat(context.getBeanNamesForType(IdempotencyPurger.class))
                        .isEmpty());
    }
}
