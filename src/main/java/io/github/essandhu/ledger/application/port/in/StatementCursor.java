package io.github.essandhu.ledger.application.port.in;

import java.time.Instant;
import java.util.Objects;

import io.github.essandhu.ledger.domain.model.PostingId;

/**
 * A keyset position in one account's statement: the {@code (posted_at, id)} of the last line
 * already seen. The next page contains only lines strictly after it in
 * {@code (posted_at, id)} order — an order that is total and append-stable because same-entry
 * legs share an id-tiebroken instant and per-account posted_at is strictly increasing across
 * entries by construction. Value object only: the opaque wire encoding, and its
 * binding to the account that issued it, are the web adapter's concern.
 */
public record StatementCursor(Instant postedAt, PostingId id) {

    public StatementCursor {
        Objects.requireNonNull(postedAt, "postedAt");
        Objects.requireNonNull(id, "id");
    }
}
