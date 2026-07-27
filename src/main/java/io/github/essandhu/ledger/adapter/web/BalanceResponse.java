package io.github.essandhu.ledger.adapter.web;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.github.essandhu.ledger.application.port.in.BalanceView;
import io.github.essandhu.ledger.domain.model.AccountType;

/**
 * Wire shape of the balance endpoint (pinned at M3). {@code balance} is the NATURAL
 * figure — raw × direction(type), the sign convention's "shown to clients" — and leads; {@code
 * rawBalance} rides along so a statement walk (whose lines carry raw signed amounts) can be
 * reconciled against it, and {@code postingCount} says how many lines that walk should visit.
 * {@code asOf} is PRESENT exactly when the figure was derived from postings at that instant
 * (ADR-0002); the live snapshot omits the field entirely — absent, never null.
 */
record BalanceResponse(
        UUID accountId,
        AccountType type,
        MoneyDto balance,
        MoneyDto rawBalance,
        long postingCount,
        @JsonInclude(JsonInclude.Include.NON_NULL) Instant asOf) {

    static BalanceResponse from(BalanceView view) {
        return new BalanceResponse(
                view.accountId().value(),
                view.type(),
                new MoneyDto(view.natural(), view.currency().value()),
                new MoneyDto(view.raw(), view.currency().value()),
                view.postingCount(),
                view.asOf().orElse(null));
    }
}
