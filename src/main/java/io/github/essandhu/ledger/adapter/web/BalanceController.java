package io.github.essandhu.ledger.adapter.web;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.essandhu.ledger.application.port.in.GetBalanceQuery;
import io.github.essandhu.ledger.domain.model.AccountId;

/** The balance read surface (M3). Thin: param ↔ query mapping only. */
@RestController
@RequestMapping("/api/v1/accounts/{id}/balance")
class BalanceController {

    private final GetBalanceQuery getBalance;

    BalanceController(GetBalanceQuery getBalance) {
        this.getBalance = getBalance;
    }

    /**
     * {@code ?at=} takes an ISO-8601 UTC instant — the {@code Z} form is the wire contract
     * (pinned at M3); offset forms are uncontracted (a raw {@code '+'} is
     * servlet-decoded to a space and rejected 400). The response's {@code asOf} is the parsed
     * instant normalized by {@link WireInstants} (floored to the microsecond grid — exact
     * against grid data), not the literal parameter text. Without {@code at}: the live
     * snapshot (ADR-0002's O(1) read).
     */
    @GetMapping
    BalanceResponse balance(@PathVariable UUID id, @RequestParam Optional<Instant> at) {
        AccountId accountId = new AccountId(id);
        return BalanceResponse.from(at
                .map(instant -> getBalance.asOf(accountId, WireInstants.normalize("at", instant)))
                .orElseGet(() -> getBalance.current(accountId)));
    }
}
