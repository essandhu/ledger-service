package io.github.essandhu.ledger.adapter.web;

import java.net.URI;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.essandhu.ledger.application.port.in.GetJournalEntryQuery;
import io.github.essandhu.ledger.application.port.in.PostJournalEntryUseCase;
import io.github.essandhu.ledger.application.port.in.PostJournalEntryUseCase.PostEntryCommand;
import io.github.essandhu.ledger.application.port.in.ReverseEntryUseCase;
import io.github.essandhu.ledger.application.port.in.ReverseEntryUseCase.ReverseCommand;
import io.github.essandhu.ledger.domain.model.AccountId;
import io.github.essandhu.ledger.domain.model.CurrencyCode;
import io.github.essandhu.ledger.domain.model.EntryDraft;
import io.github.essandhu.ledger.domain.model.EntryId;
import io.github.essandhu.ledger.domain.model.JournalEntry;
import io.github.essandhu.ledger.domain.model.Money;

/** The journal-entry surface (PLAN §5). Thin: DTO ↔ command mapping only; created_by = JWT sub (PLAN §7). */
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
    ResponseEntity<EntryResponse> post(@Valid @RequestBody PostJournalEntryRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        JournalEntry entry = postEntry.postEntry(new PostEntryCommand(request.description(),
                request.postings().stream().map(JournalEntryController::leg).toList(),
                MissingTokenSubject.requiredSubject(jwt)));
        return created(entry);
    }

    @GetMapping("/{id}")
    EntryResponse byId(@PathVariable UUID id) {
        return EntryResponse.from(getEntry.byId(new EntryId(id)));
    }

    @PostMapping("/{id}/reversal")
    ResponseEntity<EntryResponse> reverse(@PathVariable UUID id,
            @Valid @RequestBody(required = false) ReversalRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        // The body is optional in its entirety (a reversal needs only its path id); an absent
        // body means an absent description, same as an absent field. The subject guard runs
        // before the use case, so a sub-less token gets its 401 without probing entry existence.
        JournalEntry entry = reverseEntry.reverse(new ReverseCommand(new EntryId(id),
                request == null ? null : request.description(),
                MissingTokenSubject.requiredSubject(jwt)));
        return created(entry);
    }

    private static ResponseEntity<EntryResponse> created(JournalEntry entry) {
        return ResponseEntity
                .created(URI.create("/api/v1/journal-entries/" + entry.id().value()))
                .body(EntryResponse.from(entry));
    }

    private static EntryDraft.Leg leg(PostingLegRequest posting) {
        return new EntryDraft.Leg(new AccountId(posting.accountId()),
                Money.of(posting.amount().amount(), new CurrencyCode(posting.amount().currency())));
    }
}
