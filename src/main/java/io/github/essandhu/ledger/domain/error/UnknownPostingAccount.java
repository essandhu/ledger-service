package io.github.essandhu.ledger.domain.error;

import java.util.Set;
import java.util.stream.Collectors;

import io.github.essandhu.ledger.domain.model.AccountId;

/**
 * An account referenced inside a posting payload does not exist. Deliberately NOT
 * {@code AccountNotFound}: a path id that resolves to nothing is a 404 (the resource is the
 * URL), but a payload id that resolves to nothing is a 422 — the resource (the entry
 * collection) exists, the request semantics are refused (the API contract; the M1 advice javadocs pin
 * "do not fold the two together"). Detected under the balance lock as missing snapshot rows —
 * every real account has one by construction (V3 backfill + create-account-transaction insert).
 * Carries every unknown id, not just the first, so one round-trip reports the whole damage.
 */
public class UnknownPostingAccount extends RuntimeException {

    private final Set<AccountId> accountIds;

    public UnknownPostingAccount(Set<AccountId> accountIds) {
        super("unknown account(s) referenced in postings: %s".formatted(render(accountIds)));
        this.accountIds = Set.copyOf(accountIds);
    }

    public Set<AccountId> accountIds() {
        return accountIds;
    }

    private static String render(Set<AccountId> accountIds) {
        // Sorted so the message is deterministic — sets have no order, error messages should.
        return accountIds.stream()
                .map(id -> id.value().toString())
                .sorted()
                .collect(Collectors.joining(", "));
    }
}
