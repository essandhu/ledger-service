package io.github.essandhu.ledger.adapter.web;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import io.github.essandhu.ledger.application.port.in.PostingOutcome;
import io.github.essandhu.ledger.domain.model.AccountId;
import io.github.essandhu.ledger.domain.model.CurrencyCode;
import io.github.essandhu.ledger.domain.model.EntryDraft;
import io.github.essandhu.ledger.domain.model.EntryId;
import io.github.essandhu.ledger.domain.model.EntryType;
import io.github.essandhu.ledger.domain.model.JournalEntry;
import io.github.essandhu.ledger.domain.model.Money;
import io.github.essandhu.ledger.domain.model.PostingId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The shared write-response mapping and ADR-0004's lost-race retry, deterministically: the
 * 2-thread integration race only exercises the retry when the requests actually collide, so
 * the retry-once mechanic — catch the unique-violation, re-invoke exactly once, propagate a
 * second loss — is pinned here where the interleaving is under test control.
 */
@DisplayName("PostingResponses: outcome mapping and the ADR-0004 lost-race retry")
class PostingResponsesTest {

    private static final CurrencyCode EUR = new CurrencyCode("EUR");

    private static JournalEntry entry() {
        AccountId a = new AccountId(UUID.fromString("019817b4-0000-7000-8000-00000000000a"));
        AccountId b = new AccountId(UUID.fromString("019817b4-0000-7000-8000-00000000000b"));
        EntryDraft draft = new EntryDraft(null, List.of(
                new EntryDraft.Leg(a, Money.of(5, EUR)),
                new EntryDraft.Leg(b, Money.of(-5, EUR))));
        return JournalEntry.post(
                new EntryId(UUID.fromString("019817b4-0000-7000-8000-0000000000e1")),
                EntryType.JOURNAL, draft, null, "responses-tester", "responses-key",
                Instant.parse("2026-07-23T10:00:00Z"),
                List.of(new PostingId(UUID.fromString("019817b4-0000-7000-8000-0000000000f1")),
                        new PostingId(UUID.fromString("019817b4-0000-7000-8000-0000000000f2"))));
    }

    @Test
    @DisplayName("a lost race is retried exactly once, and the retry's outcome is the answer")
    void lost_race_is_retried_once() {
        AtomicInteger calls = new AtomicInteger();
        PostingOutcome replay = new PostingOutcome.Replayed("{\"winner\":true}");

        PostingOutcome outcome = PostingResponses.withLostRaceRetry(() -> {
            if (calls.incrementAndGet() == 1) {
                throw new DataIntegrityViolationException("journal_entry_idem_backstop");
            }
            return replay;
        });

        assertThat(outcome).isSameAs(replay);
        assertThat(calls).hasValue(2);
    }

    @Test
    @DisplayName("a second consecutive loss propagates — the retry is bounded at one")
    void second_loss_propagates() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> PostingResponses.withLostRaceRetry(() -> {
            calls.incrementAndGet();
            throw new DataIntegrityViolationException("journal_entry_idem_backstop");
        })).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(calls).hasValue(2);
    }

    @Test
    @DisplayName("a clean first call never retries")
    void clean_call_does_not_retry() {
        AtomicInteger calls = new AtomicInteger();
        PostingOutcome posted = new PostingOutcome.Posted(entry());

        assertThat(PostingResponses.withLostRaceRetry(() -> {
            calls.incrementAndGet();
            return posted;
        })).isSameAs(posted);
        assertThat(calls).hasValue(1);
    }

    @Test
    @DisplayName("Posted → 201 + Location; Replayed → 200 + Idempotency-Replayed: true and NO Location (PLAN §5 M4 pin)")
    void outcome_mapping_is_the_pinned_split() {
        JournalEntry entry = entry();

        ResponseEntity<?> created = PostingResponses.respond(new PostingOutcome.Posted(entry));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getHeaders().getLocation())
                .isEqualTo(URI.create("/api/v1/journal-entries/" + entry.id().value()));
        assertThat(created.getHeaders().getFirst(PostingResponses.IDEMPOTENCY_REPLAYED)).isNull();
        assertThat(created.getBody()).isEqualTo(EntryResponse.from(entry));

        ResponseEntity<?> replayed = PostingResponses.respond(
                new PostingOutcome.Replayed("{\"stored\":true}"));
        assertThat(replayed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replayed.getHeaders().getFirst(PostingResponses.IDEMPOTENCY_REPLAYED))
                .isEqualTo("true");
        assertThat(replayed.getHeaders().getLocation())
                .as("nothing was created by a replay — no Location (PLAN §5)").isNull();
        assertThat(replayed.getBody()).isEqualTo("{\"stored\":true}");
    }
}
