package io.github.essandhu.ledger.domain.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Identity of a {@link Posting}. UUIDv7 in practice (time-ordered for index locality,
 * The schema) — generated application-side behind the {@code IdGenerator} port; the domain
 * neither generates ids nor cares about the version bits.
 */
public record PostingId(UUID value) {

    public PostingId {
        Objects.requireNonNull(value, "value");
    }
}
