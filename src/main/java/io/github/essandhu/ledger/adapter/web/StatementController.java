package io.github.essandhu.ledger.adapter.web;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.essandhu.ledger.application.port.in.GetStatementQuery;
import io.github.essandhu.ledger.application.port.in.StatementCursor;
import io.github.essandhu.ledger.application.port.in.StatementFilter;
import io.github.essandhu.ledger.application.port.in.StatementSpec;
import io.github.essandhu.ledger.domain.model.AccountId;

/** The statement surface (M3). Thin: param ↔ query mapping only. */
@RestController
@RequestMapping("/api/v1/accounts/{id}/postings")
class StatementController {

    private final GetStatementQuery getStatement;

    StatementController(GetStatementQuery getStatement) {
        this.getStatement = getStatement;
    }

    /**
     * The window is {@code (from, to]} — from EXCLUSIVE (the opening-balance boundary), to
     * INCLUSIVE — so {@code asOf(from) + Σ lines = asOf(to)} holds exactly (I10). Both bounds
     * optional, ISO-8601 instants with the same {@code Z}-form caveat as {@code ?at=},
     * normalized by {@link WireInstants}. The cursor is opaque and only valid for this
     * account's statement; a token from anywhere else is a 400. Query-param shape violations
     * (cursor, bounds, limit) are decided BEFORE the account-existence 404 — the same
     * precedence every binding-level 400 already has.
     */
    @GetMapping
    StatementPageResponse postings(@PathVariable UUID id,
            @RequestParam Optional<Instant> from,
            @RequestParam Optional<Instant> to,
            @RequestParam Optional<String> cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(StatementSpec.MAX_LIMIT) int limit) {
        AccountId accountId = new AccountId(id);
        Optional<StatementCursor> after =
                cursor.map(token -> StatementCursorCodec.decode(accountId, token));
        return StatementPageResponse.from(accountId, getStatement.statement(accountId,
                new StatementFilter(from.map(value -> WireInstants.normalize("from", value)),
                        to.map(value -> WireInstants.normalize("to", value))),
                new StatementSpec(after, limit)));
    }
}
