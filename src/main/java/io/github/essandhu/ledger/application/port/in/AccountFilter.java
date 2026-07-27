package io.github.essandhu.ledger.application.port.in;

import java.util.Objects;
import java.util.Optional;

import io.github.essandhu.ledger.domain.model.AccountStatus;
import io.github.essandhu.ledger.domain.model.AccountType;

/** Listing filter: both dimensions optional, combined with AND. */
public record AccountFilter(Optional<AccountType> type, Optional<AccountStatus> status) {

    public AccountFilter {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(status, "status");
    }

    public static AccountFilter none() {
        return new AccountFilter(Optional.empty(), Optional.empty());
    }
}
