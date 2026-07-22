package io.github.essandhu.ledger.adapter.web;

import java.time.Instant;
import java.util.UUID;

import io.github.essandhu.ledger.domain.model.Account;
import io.github.essandhu.ledger.domain.model.AccountStatus;
import io.github.essandhu.ledger.domain.model.AccountType;

/**
 * Account representation (PLAN §5). The optimistic-lock {@code version} is deliberately not
 * exposed: v1 offers no client-driven concurrency control (no If-Match); the guard protects
 * overlapping writes server-side, and ETag/If-Match is the recorded upgrade path if
 * human-edit-window protection is ever wanted.
 */
record AccountResponse(
        UUID id,
        String name,
        String currency,
        AccountType type,
        AccountStatus status,
        boolean allowNegative,
        Instant createdAt,
        Instant updatedAt) {

    static AccountResponse from(Account account) {
        return new AccountResponse(account.id().value(), account.name(), account.currency().value(),
                account.type(), account.status(), account.allowNegative(),
                account.createdAt(), account.updatedAt());
    }
}
