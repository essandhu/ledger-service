package io.github.essandhu.ledger.application.port.out;

import java.util.Objects;

import io.github.essandhu.ledger.domain.model.JournalEntry;

/**
 * Renders the success response that will be STORED for replays (ADR-0004: "the stored original
 * response body"). Implemented by the web adapter with the very mapper MVC serializes with, so
 * the stored body is byte-identical to what the first caller received — the application core
 * neither knows nor cares that the rendering is JSON-by-Jackson, it only requires "exactly what
 * the client saw". Called INSIDE the posting transaction, between the entry insert and the
 * record insert; a rendering failure therefore rolls the whole posting back rather than
 * committing an entry whose replay evidence was never written.
 */
@FunctionalInterface
public interface WriteResponseRenderer {

    Rendered render(JournalEntry entry);

    /** The response as first served: {@code status} is audit data (replays always answer 200
     * per the API contract); {@code body} is returned verbatim on replay. */
    record Rendered(int status, String body) {
        public Rendered {
            Objects.requireNonNull(body, "body");
        }
    }
}
