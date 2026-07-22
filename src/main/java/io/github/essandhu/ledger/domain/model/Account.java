package io.github.essandhu.ledger.domain.model;

import java.time.Instant;
import java.util.Objects;

import io.github.essandhu.ledger.domain.error.AccountClosed;
import io.github.essandhu.ledger.domain.error.InvalidAccountInput;
import io.github.essandhu.ledger.domain.error.InvalidStatusTransition;

/**
 * A bucket of value in exactly one currency (PLAN §4.1). Immutable: every mutation returns a
 * new instance; a mutation that would change nothing returns {@code this}, so callers can use
 * identity to decide whether anything needs persisting (no phantom writes, no version bumps on
 * declarative no-ops).
 *
 * <p>I12, lifecycle half (M1): edge legality is enforced here. The other close precondition —
 * natural balance must be zero — is the posting half and lands in M2 as a separate domain rule
 * the use case evaluates while holding the account_balance lock (PLAN §4.5); this type's
 * signatures do not change for it.
 *
 * <p>Optimistic-lock bookkeeping (the {@code version} column) is deliberately absent: it is
 * persistence infrastructure owned by the JPA adapter, not a domain concept.
 */
public record Account(
        AccountId id,
        String name,
        CurrencyCode currency,
        AccountType type,
        AccountStatus status,
        boolean allowNegative,
        Instant createdAt,
        Instant updatedAt) {

    public Account {
        Objects.requireNonNull(id, "id");
        requireValidName(name);
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /** Opens a new ACTIVE account; {@code now} comes from the injected Clock (TEST-STRATEGY §1). */
    public static Account open(AccountId id, String name, CurrencyCode currency, AccountType type,
            boolean allowNegative, Instant now) {
        return new Account(id, name, currency, type, AccountStatus.ACTIVE, allowNegative, now, now);
    }

    /**
     * The I12 state machine, in one place. Same-state is a no-op returning {@code this}
     * (declarative PATCH semantics — account management carries no Idempotency-Key, so natural
     * idempotence is its only retry story); from CLOSED no edge exists.
     */
    public Account transitionTo(AccountStatus target, Instant now) {
        Objects.requireNonNull(target, "target");
        if (target == status) {
            return this;
        }
        if (status == AccountStatus.CLOSED) {
            throw new InvalidStatusTransition(status, target);
        }
        return new Account(id, name, currency, type, target, allowNegative, createdAt, now);
    }

    /** Renaming is a metadata edit: allowed while ACTIVE or FROZEN (freezing blocks postings,
     * not metadata), never on CLOSED. The no-op check runs BEFORE the closed guard, mirroring
     * {@link #transitionTo}: asserting the current state must always be a no-op, or the retried
     * combined rename+close PATCH (which finds the account already renamed and closed) would
     * spuriously 422 — and natural idempotence is this API's only retry story. */
    public Account rename(String newName, Instant now) {
        requireValidName(newName);
        if (newName.equals(name)) {
            return this;
        }
        if (status == AccountStatus.CLOSED) {
            throw new AccountClosed("account %s is closed; closed accounts reject edits".formatted(id.value()));
        }
        return new Account(id, newName, currency, type, status, allowNegative, createdAt, now);
    }

    private static void requireValidName(String name) {
        if (name == null || name.isBlank()) {
            throw new InvalidAccountInput("account name must not be blank");
        }
        if (name.length() > 200) {
            throw new InvalidAccountInput("account name must be at most 200 characters");
        }
        // U+0000 is legal JSON but not storable in PostgreSQL text — without this guard it
        // would surface as a 500 at the JDBC boundary instead of a 400 here. The other control
        // characters are storable but never legitimate in a display name.
        if (name.chars().anyMatch(Character::isISOControl)) {
            throw new InvalidAccountInput("account name must not contain control characters");
        }
    }
}
