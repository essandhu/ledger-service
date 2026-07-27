package io.github.essandhu.ledger.domain.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Identity of a {@link JournalEntry}. UUIDv7 in practice (time-ordered for index locality,
 * The schema) — generated application-side behind the {@code IdGenerator} port; the domain
 * neither generates ids nor cares about the version bits.
 */
public record EntryId(UUID value) {

    public EntryId {
        Objects.requireNonNull(value, "value");
    }
}
