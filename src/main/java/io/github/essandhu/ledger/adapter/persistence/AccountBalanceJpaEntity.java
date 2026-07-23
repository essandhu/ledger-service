package io.github.essandhu.ledger.adapter.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.springframework.data.domain.Persistable;

import io.github.essandhu.ledger.domain.model.AccountBalance;
import io.github.essandhu.ledger.domain.model.AccountId;

/**
 * JPA face of the {@code account_balance} row (ADR-0002): the one snapshot per account that
 * the posting engine locks and bumps. Deliberately NOT {@code @Immutable} — this row mutates —
 * yet the entity still has no mutators and no {@code apply(...)}: its only write path is the
 * one-time zero seed, because the bump goes through the set-based
 * {@code balance = balance + :delta} statement in {@link AccountBalanceJpaRepository}, never
 * through a managed instance (ADR-0002 rules out read-modify-write — a stale in-memory
 * balance flushed by dirty checking could silently overwrite committed postings; a
 * database-side addition cannot). No equals/hashCode: entities are never held in sets or
 * compared, and dead code is a coverage and correctness liability.
 *
 * <p>{@link Persistable} answers Spring Data's isNew question: V3 gives this table no version
 * column (its concurrency control is the pessimistic row lock, ADR-0003 — never
 * {@code account.version}), so the entity states the fact directly — the only instances ever
 * saved are the fresh zero seeds of {@link #zero}. Without it, {@code save()} would merge: a
 * probing SELECT per account creation.
 */
@Entity
@Table(name = "account_balance")
class AccountBalanceJpaEntity implements Persistable<UUID> {

    @Id
    private UUID accountId;

    private long balance;

    private long postingCount;

    private Instant updatedAt;

    protected AccountBalanceJpaEntity() {
        // JPA
    }

    /** The zero seed of the V2 forward-contract: balance 0, no postings yet, stamped with the
     * account's creation instant — exactly what the V3 backfill wrote for M1-era accounts. */
    static AccountBalanceJpaEntity zero(AccountId accountId, Instant createdAt) {
        AccountBalanceJpaEntity entity = new AccountBalanceJpaEntity();
        entity.accountId = accountId.value();
        entity.balance = 0;
        entity.postingCount = 0;
        entity.updatedAt = createdAt;
        return entity;
    }

    /** Rebuilds through the domain constructor, so its invariants (the non-negative watermark)
     * re-run on every load — database corruption surfaces loudly, not as a quietly-invalid
     * domain object. */
    AccountBalance toDomain() {
        return new AccountBalance(new AccountId(accountId), balance, postingCount, updatedAt);
    }

    @Override
    public UUID getId() {
        return accountId;
    }

    @Override
    public boolean isNew() {
        // True by construction: the only saved instances are fresh zero seeds; loaded
        // instances are read snapshots that no code path saves back (see class javadoc).
        return true;
    }
}
