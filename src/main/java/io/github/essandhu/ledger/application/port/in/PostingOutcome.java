package io.github.essandhu.ledger.application.port.in;

import java.util.Objects;

import io.github.essandhu.ledger.domain.model.JournalEntry;

/**
 * What a money-moving use case produced (ADR-0004): either a freshly {@link Posted} entry, or
 * a {@link Replayed} answer served from the idempotency record of an earlier success with the
 * same (principal, key, payload hash). Sealed so the web adapter's response mapping is an
 * exhaustive switch — a new outcome kind cannot ship without a response decision.
 *
 * <p>The third possible ending — same key, DIFFERENT payload hash — is not an outcome but the
 * {@link IdempotencyKeyConflict} rejection: a client bug surfaced loudly, nothing posted.
 */
public sealed interface PostingOutcome {

    /** A new entry was posted; the caller renders its 201 as always. */
    record Posted(JournalEntry entry) implements PostingOutcome {
        public Posted {
            Objects.requireNonNull(entry, "entry");
        }
    }

    /**
     * Served from the idempotency record: {@code responseBody} is the original success
     * response, byte for byte (rendered by the web adapter's own mapper at first-post time and
     * stored verbatim — ADR-0004's "stored original response body"). The caller answers 200
     * with it plus {@code Idempotency-Replayed: true}; nothing was executed, locked, or
     * written on this request.
     */
    record Replayed(String responseBody) implements PostingOutcome {
        public Replayed {
            Objects.requireNonNull(responseBody, "responseBody");
        }
    }
}
