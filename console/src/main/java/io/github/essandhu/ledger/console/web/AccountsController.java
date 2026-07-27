package io.github.essandhu.ledger.console.web;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import io.github.essandhu.ledger.console.api.LedgerApi.Account;
import io.github.essandhu.ledger.console.api.LedgerApi.AccountPage;
import io.github.essandhu.ledger.console.api.LedgerApi.AccountStatus;
import io.github.essandhu.ledger.console.api.LedgerApi.AccountType;
import io.github.essandhu.ledger.console.api.LedgerApiClient;

/**
 * The accounts list: server-side type/status filters (the API's own query params, forwarded
 * verbatim) over offset paging. The API sends no totalPages — the page computes it.
 */
@Controller
class AccountsController {

    static final int PAGE_SIZE = 20;

    private final LedgerApiClient api;

    AccountsController(LedgerApiClient api) {
        this.api = api;
    }

    record AccountRow(UUID id, String name, String currency, AccountType type,
            AccountStatus status, boolean allowNegative, Instant createdAt) {

        /** Locale-pinned: request-locale lowercasing (Turkish dotless i) breaks CSS keys. */
        public String statusCss() {
            return status.name().toLowerCase(Locale.ROOT);
        }

        static AccountRow from(Account account) {
            return new AccountRow(account.id(), account.name(), account.currency(),
                    account.type(), account.status(), account.allowNegative(),
                    account.createdAt());
        }
    }

    @GetMapping("/accounts")
    String accounts(
            @RequestParam Optional<AccountType> type,
            @RequestParam Optional<AccountStatus> status,
            @RequestParam(defaultValue = "0") int page,
            Model model) {
        int safePage = Math.max(0, page);
        AccountPage result = api.accounts(type, status, safePage, PAGE_SIZE);

        model.addAttribute("rows", result.content().stream().map(AccountRow::from).toList());
        model.addAttribute("typeFilter", type.orElse(null));
        model.addAttribute("statusFilter", status.orElse(null));
        model.addAttribute("types", AccountType.values());
        model.addAttribute("statuses", AccountStatus.values());
        model.addAttribute("page", result.page());
        model.addAttribute("totalElements", result.totalElements());
        model.addAttribute("totalPages", result.totalPages());
        model.addAttribute("hasPrevious", result.page() > 0);
        model.addAttribute("hasNext", result.page() + 1 < result.totalPages());
        return "accounts";
    }
}
