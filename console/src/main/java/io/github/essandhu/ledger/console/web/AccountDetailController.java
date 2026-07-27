package io.github.essandhu.ledger.console.web;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import io.github.essandhu.ledger.console.api.LedgerApi.Balance;
import io.github.essandhu.ledger.console.api.LedgerApi.StatementLine;
import io.github.essandhu.ledger.console.api.LedgerApi.StatementPage;
import io.github.essandhu.ledger.console.api.LedgerApiClient;

/**
 * Account detail: the balance card (NATURAL figure headline — raw × direction(type), the
 * core's own convention — with the raw figure beside it for reconciliation), the as-of
 * picker, and the statement — oldest-first as the API orders it, appended in place by the
 * htmx load-more sentinel.
 *
 * <p>"More to load" keys off {@code content.size() == limit}, never off {@code nextCursor}:
 * the cursor is a resume position that is present even on the last page (the mapped
 * statement contract).
 */
@Controller
class AccountDetailController {

    static final int STATEMENT_LIMIT = 20;

    private final LedgerApiClient api;

    AccountDetailController(LedgerApiClient api) {
        this.api = api;
    }

    /** One statement row, presentation-ready: raw signed amount, side derived from sign. */
    record StatementRow(UUID postingId, UUID entryId, String amount, String side,
            Instant postedAt) {

        /** Locale-pinned CSS key — request-locale lowercasing would break it. */
        public String sideCss() {
            return side.toLowerCase(Locale.ROOT);
        }

        static StatementRow from(StatementLine line) {
            return new StatementRow(line.id(), line.entryId(), MoneyFormat.format(line.amount()),
                    line.amount().amount() >= 0 ? "DEBIT" : "CREDIT", line.postedAt());
        }
    }

    record BalanceCard(String natural, String raw, long postingCount, Instant asOf,
            boolean negative) {

        static BalanceCard from(Balance balance) {
            return new BalanceCard(
                    MoneyFormat.format(balance.balance()),
                    MoneyFormat.format(balance.rawBalance()),
                    balance.postingCount(),
                    balance.asOf(),
                    balance.balance().amount() < 0);
        }
    }

    @GetMapping("/accounts/{id}")
    String account(
            @PathVariable UUID id,
            @RequestParam Optional<String> at,
            Model model) {
        Optional<Instant> asOf = Optional.empty();
        if (at.isPresent() && !at.get().isBlank()) {
            try {
                asOf = Optional.of(Instant.parse(at.get().trim()));
            } catch (DateTimeParseException e) {
                // An unparseable picker value is a form error, not an API error: render the
                // live balance with the complaint inline, keeping the page usable.
                model.addAttribute("atError",
                        "Not an ISO-8601 UTC instant (example: 2026-07-27T12:00:00Z)");
            }
        }

        var account = api.account(id);
        model.addAttribute("account", account);
        model.addAttribute("statusCss", account.status().name().toLowerCase(Locale.ROOT));
        model.addAttribute("balance", BalanceCard.from(api.balance(id, asOf)));
        model.addAttribute("at", at.filter(s -> !s.isBlank()).orElse(null));

        StatementPage statement = api.statement(id, Optional.empty(), STATEMENT_LIMIT);
        addStatementModel(model, id, statement);
        return "account";
    }

    /**
     * The load-more fragment: rows plus, when the page ran full, a fresh sentinel carrying
     * the next cursor (the htmx click-to-load pattern — the sentinel row swaps itself out
     * via {@code hx-swap="outerHTML"}). A non-htmx hit of this URL has no page to embed
     * into, so it bounces to the account page.
     */
    @GetMapping("/accounts/{id}/statement")
    String statementPage(
            @PathVariable UUID id,
            @RequestParam String cursor,
            @RequestHeader(value = "HX-Request", required = false) String hxRequest,
            Model model) {
        if (!"true".equals(hxRequest)) {
            return "redirect:/accounts/" + id;
        }
        addStatementModel(model, id, api.statement(id, Optional.of(cursor), STATEMENT_LIMIT));
        return "statement-rows :: statementRows";
    }

    private static void addStatementModel(Model model, UUID accountId, StatementPage page) {
        List<StatementRow> rows = page.content().stream().map(StatementRow::from).toList();
        model.addAttribute("accountId", accountId);
        model.addAttribute("lines", rows);
        model.addAttribute("moreAvailable", rows.size() == STATEMENT_LIMIT);
        model.addAttribute("nextCursor", page.nextCursor());
    }
}
