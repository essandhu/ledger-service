package io.github.essandhu.ledger.adapter.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import io.github.essandhu.ledger.domain.model.Account;
import io.github.essandhu.ledger.domain.model.AccountId;
import io.github.essandhu.ledger.domain.model.AccountStatus;
import io.github.essandhu.ledger.domain.model.AccountType;
import io.github.essandhu.ledger.domain.model.CurrencyCode;

/**
 * JPA face of the {@code account} row. {@code @Version} is the optimistic guard for concurrent
 * metadata edits (PLAN §4.3) — a {@code Long} so null marks a not-yet-persisted entity (Spring
 * Data's isNew check ⇒ INSERT without a preceding merge SELECT). No equals/hashCode: entities
 * are never held in sets or compared, and dead code is a coverage and correctness liability.
 * {@link #toDomain()} rebuilds through the domain constructor, so its invariants re-run on
 * every load — database corruption surfaces loudly, not as a quietly-invalid domain object.
 */
@Entity
@Table(name = "account")
class AccountJpaEntity {

    @Id
    private UUID id;

    private String name;

    private String currency;

    @Enumerated(EnumType.STRING)
    private AccountType type;

    @Enumerated(EnumType.STRING)
    private AccountStatus status;

    private boolean allowNegative;

    @Version
    private Long version;

    private Instant createdAt;

    private Instant updatedAt;

    protected AccountJpaEntity() {
        // JPA
    }

    static AccountJpaEntity fromDomain(Account account) {
        AccountJpaEntity entity = new AccountJpaEntity();
        entity.id = account.id().value();
        entity.name = account.name();
        entity.currency = account.currency().value();
        entity.type = account.type();
        entity.status = account.status();
        entity.allowNegative = account.allowNegative();
        entity.createdAt = account.createdAt();
        entity.updatedAt = account.updatedAt();
        return entity;
    }

    /** Copies the mutable state (rename/lifecycle); id, currency, type, allowNegative,
     * createdAt never change after creation. */
    void apply(Account account) {
        this.name = account.name();
        this.status = account.status();
        this.updatedAt = account.updatedAt();
    }

    Account toDomain() {
        return new Account(new AccountId(id), name, new CurrencyCode(currency), type, status,
                allowNegative, createdAt, updatedAt);
    }

    UUID id() {
        return id;
    }
}
