package io.github.essandhu.ledger.adapter.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import org.springframework.stereotype.Component;

import io.github.essandhu.ledger.application.port.out.BalanceRepository;
import io.github.essandhu.ledger.domain.model.AccountBalance;
import io.github.essandhu.ledger.domain.model.AccountId;

/**
 * JPA implementation of the {@link BalanceRepository} port — the database half of the single
 * lock site (ADR-0003). Transactions are owned by the application services; repository calls
 * join the surrounding transaction, which is precisely what makes the FOR UPDATE locks, the
 * entry insert, and the snapshot bumps one atomic unit (ADR-0002). Micrometer is legal here —
 * the ArchUnit I14 rules keep it out of the application core, so the lock-wait timer lives
 * with the query that does the waiting (PLAN §8).
 */
@Component
class BalancePersistenceAdapter implements BalanceRepository {

    /** PLAN §8: time spent acquiring the FOR UPDATE locks, measured where the waiting
     * happens — under contention this timer, not CPU, is the posting latency story. */
    private static final String LOCK_WAIT = "ledger.posting.lock.wait";

    private final AccountBalanceJpaRepository repository;
    private final MeterRegistry registry;

    BalancePersistenceAdapter(AccountBalanceJpaRepository repository, MeterRegistry registry) {
        this.repository = repository;
        this.registry = registry;
    }

    @Override
    public List<AccountBalance> lockBalances(List<AccountId> idsInCanonicalOrder) {
        // Defense in depth (ADR-0003): the service pre-sorts and the unit-test fake polices
        // that contract, but deadlock freedom must not hang on caller discipline — the query's
        // ORDER BY re-sorts unconditionally, and this Java-side sort+dedupe keeps every
        // representation of the lock set (bind order, logs, the SQL) telling the same
        // canonical story even for a caller that broke the rule.
        List<UUID> ids = idsInCanonicalOrder.stream()
                .distinct()
                .sorted(CANONICAL_ORDER)
                .map(AccountId::value)
                .toList();
        if (ids.isEmpty()) {
            // No flow produces an empty lock set (drafts have legs, lifecycle locks one id),
            // but a native IN () would be a SQL syntax error — the degenerate answer is here.
            return List.of();
        }
        // A failed acquisition (statement timeout, cancellation) discards its sample: the
        // metric is the wait successful lock takers experienced, the same outcome discipline
        // as MeteredPostingUseCases.
        Timer.Sample sample = Timer.start(registry);
        List<AccountBalanceJpaEntity> locked = repository.lockAllInCanonicalOrder(ids);
        sample.stop(registry.timer(LOCK_WAIT));
        // Query order is canonical order (the ORDER BY); missing ids are simply absent rows,
        // which the caller reads as "this account does not exist" (port contract).
        return locked.stream().map(AccountBalanceJpaEntity::toDomain).toList();
    }

    @Override
    public Optional<AccountBalance> findCurrent(AccountId accountId) {
        // Plain primary-key point SELECT — deliberately NOT lockAllInCanonicalOrder: reads
        // never join the lock queue, so ADR-0003's single lock site stays lockBalances.
        return repository.findById(accountId.value()).map(AccountBalanceJpaEntity::toDomain);
    }

    @Override
    public void applyDelta(AccountId accountId, long delta, long legCount, Instant now) {
        int updated = repository.applyDelta(accountId.value(), delta, legCount, now);
        if (updated != 1) {
            // The port contract says this runs only under the row's lock (ADR-0003), so a
            // zero-row bump means the snapshot the caller just decided on does not exist —
            // silent acceptance would drop money from the books; fail loudly instead.
            throw new IllegalStateException(
                    "applyDelta touched %d account_balance rows for %s — expected exactly 1 (ADR-0002)"
                            .formatted(updated, accountId.value()));
        }
    }

    @Override
    public void insertZero(AccountId accountId, Instant createdAt) {
        // Same-transaction seed of the V2 forward-contract; a duplicate is a programming
        // error per the port contract and surfaces as the primary-key violation at flush.
        repository.save(AccountBalanceJpaEntity.zero(accountId, createdAt));
    }
}
