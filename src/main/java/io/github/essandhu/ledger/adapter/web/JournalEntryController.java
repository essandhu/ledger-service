package io.github.essandhu.ledger.adapter.web;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.essandhu.ledger.application.port.in.GetJournalEntryQuery;
import io.github.essandhu.ledger.application.port.in.PostJournalEntryUseCase;
import io.github.essandhu.ledger.application.port.in.PostJournalEntryUseCase.PostEntryCommand;
import io.github.essandhu.ledger.application.port.in.PostingOutcome;
import io.github.essandhu.ledger.application.port.in.ReverseEntryUseCase;
import io.github.essandhu.ledger.application.port.in.ReverseEntryUseCase.ReverseCommand;
import io.github.essandhu.ledger.domain.model.AccountId;
import io.github.essandhu.ledger.domain.model.CurrencyCode;
import io.github.essandhu.ledger.domain.model.EntryDraft;
import io.github.essandhu.ledger.domain.model.EntryId;
import io.github.essandhu.ledger.domain.model.Money;

/**
 * The journal-entry surface (PLAN §5). Thin: DTO ↔ command mapping only; created_by = JWT sub
 * (PLAN §7). M4: the write endpoints require {@code Idempotency-Key} (ADR-0004) — absence is
 * the framework's own 400 before this code runs; outcome mapping and the lost-race retry live
 * in {@link PostingResponses}, shared with the transfer surface.
 */
@RestController
@RequestMapping("/api/v1/journal-entries")
class JournalEntryController {

    private final PostJournalEntryUseCase postEntry;
    private final ReverseEntryUseCase reverseEntry;
    private final GetJournalEntryQuery getEntry;

    JournalEntryController(PostJournalEntryUseCase postEntry, ReverseEntryUseCase reverseEntry,
            GetJournalEntryQuery getEntry) {
        this.postEntry = postEntry;
        this.reverseEntry = reverseEntry;
        this.getEntry = getEntry;
    }

    @PostMapping
    ResponseEntity<?> post(@Valid @RequestBody PostJournalEntryRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal Jwt jwt) {
        String createdBy = MissingTokenSubject.requiredSubject(jwt);
        PostingOutcome outcome = PostingResponses.withLostRaceRetry(() -> postEntry.postEntry(
                new PostEntryCommand(request.description(),
                        request.postings().stream().map(JournalEntryController::leg).toList(),
                        createdBy, idempotencyKey)));
        return PostingResponses.respond(outcome);
    }

    @GetMapping("/{id}")
    EntryResponse byId(@PathVariable UUID id) {
        return EntryResponse.from(getEntry.byId(new EntryId(id)));
    }

    @PostMapping("/{id}/reversal")
    ResponseEntity<?> reverse(@PathVariable UUID id,
            @Valid @RequestBody(required = false) ReversalRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal Jwt jwt) {
        // The body is optional in its entirety (a reversal needs only its path id); an absent
        // body means an absent description, same as an absent field. The subject guard runs
        // before the use case, so a sub-less token gets its 401 without probing entry existence.
        String createdBy = MissingTokenSubject.requiredSubject(jwt);
        PostingOutcome outcome = PostingResponses.withLostRaceRetry(() -> reverseEntry.reverse(
                new ReverseCommand(new EntryId(id),
                        request == null ? null : request.description(),
                        createdBy, idempotencyKey)));
        return PostingResponses.respond(outcome);
    }

    private static EntryDraft.Leg leg(PostingLegRequest posting) {
        return new EntryDraft.Leg(new AccountId(posting.accountId()),
                Money.of(posting.amount().amount(), new CurrencyCode(posting.amount().currency())));
    }
}
