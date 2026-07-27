package io.github.essandhu.ledger.adapter.web;

import java.net.URI;
import java.util.function.Supplier;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import io.github.essandhu.ledger.application.port.in.PostingOutcome;

/**
 * The one response mapping for all three money-moving endpoints (M4): fresh post → 201 +
 * Location + the body every creating endpoint shares; replay → 200 + the STORED original body
 * verbatim + {@code Idempotency-Replayed: true} (no Location: nothing was created
 * by THIS request). Exhaustive over the sealed {@link PostingOutcome}.
 */
final class PostingResponses {

    /** The replay marker header (ADR-0004 / the IETF idempotency draft's advertised marker). */
    static final String IDEMPOTENCY_REPLAYED = "Idempotency-Replayed";

    private PostingResponses() {
    }

    /**
     * ADR-0004 §Mechanics, the concurrent-duplicate ending: the race loser's transaction dies
     * on the (created_by, idempotency_key) unique index when the winner commits — surfacing
     * here as {@link DataIntegrityViolationException} — and "the handler re-reads the
     * now-committed record in a fresh transaction and answers replay or conflict". Re-invoking
     * the use case IS that fresh-transaction re-read: it finds the winner's record and replays
     * (or conflicts), and if the winner ABORTED it finds nothing and proceeds as a first
     * attempt, exactly as the ADR requires. One retry: a second consecutive loss means
     * something other than the arbitrated race is violating integrity, and that must surface,
     * not loop.
     */
    static PostingOutcome withLostRaceRetry(Supplier<PostingOutcome> write) {
        try {
            return write.get();
        } catch (DataIntegrityViolationException lostRace) {
            return write.get();
        }
    }

    static ResponseEntity<?> respond(PostingOutcome outcome) {
        return switch (outcome) {
            case PostingOutcome.Posted posted -> ResponseEntity
                    .created(URI.create("/api/v1/journal-entries/" + posted.entry().id().value()))
                    .body(EntryResponse.from(posted.entry()));
            case PostingOutcome.Replayed replayed -> ResponseEntity.ok()
                    .header(IDEMPOTENCY_REPLAYED, "true")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(replayed.responseBody());
        };
    }
}
