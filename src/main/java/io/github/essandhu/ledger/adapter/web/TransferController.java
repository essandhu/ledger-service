package io.github.essandhu.ledger.adapter.web;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.essandhu.ledger.application.port.in.PostingOutcome;
import io.github.essandhu.ledger.application.port.in.TransferFundsUseCase;
import io.github.essandhu.ledger.application.port.in.TransferFundsUseCase.TransferCommand;
import io.github.essandhu.ledger.domain.model.AccountId;
import io.github.essandhu.ledger.domain.model.CurrencyCode;
import io.github.essandhu.ledger.domain.model.Money;

/**
 * The transfer surface (PLAN §5): a command endpoint over the same posting engine. Thin: DTO ↔
 * command mapping only. The Location points into /journal-entries — what a transfer CREATES is
 * a journal entry; /transfers itself has no item resource to address. M4: requires
 * {@code Idempotency-Key} (ADR-0004), same shared response mapping as the journal surface.
 */
@RestController
@RequestMapping("/api/v1/transfers")
class TransferController {

    private final TransferFundsUseCase transferFunds;

    TransferController(TransferFundsUseCase transferFunds) {
        this.transferFunds = transferFunds;
    }

    @PostMapping
    ResponseEntity<?> transfer(@Valid @RequestBody TransferRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal Jwt jwt) {
        String createdBy = MissingTokenSubject.requiredSubject(jwt);
        PostingOutcome outcome = PostingResponses.withLostRaceRetry(() -> transferFunds.transfer(
                new TransferCommand(
                        new AccountId(request.sourceAccountId()),
                        new AccountId(request.targetAccountId()),
                        Money.of(request.amount().amount(),
                                new CurrencyCode(request.amount().currency())),
                        request.description(), createdBy, idempotencyKey)));
        return PostingResponses.respond(outcome);
    }
}
