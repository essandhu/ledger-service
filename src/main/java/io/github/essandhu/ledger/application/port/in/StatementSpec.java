package io.github.essandhu.ledger.application.port.in;

import java.util.Objects;
import java.util.Optional;

/**
 * Page size and resume position for the keyset statement walk (PLAN §5). The web layer
 * validates request params first (400); these guards are the port's own contract for non-HTTP
 * callers — the same dual-validation stance as {@link PageSpec}, which stays the offset-paging
 * spec for account listings while statements page by keyset.
 */
public record StatementSpec(Optional<StatementCursor> after, int limit) {

    /** Same ceiling as {@link PageSpec#MAX_SIZE}: one knob's story, two paging styles. */
    public static final int MAX_LIMIT = 100;

    public StatementSpec {
        Objects.requireNonNull(after, "after");
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException(
                    "limit must be in [1, %d], got %d".formatted(MAX_LIMIT, limit));
        }
    }

    public static StatementSpec firstPage(int limit) {
        return new StatementSpec(Optional.empty(), limit);
    }
}
