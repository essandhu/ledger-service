package io.github.essandhu.ledger.domain.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Identity of an {@link Account}. UUIDv7 in practice (time-ordered for index locality,
 * PLAN §4.3) — generated application-side behind the {@code IdGenerator} port; the domain
 * neither generates ids nor cares about the version bits.
 */
public record AccountId(UUID value) {

    public AccountId {
        Objects.requireNonNull(value, "value");
    }
}
