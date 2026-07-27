package io.github.essandhu.ledger.console.web;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import io.github.essandhu.ledger.console.api.LedgerApi.Entry;
import io.github.essandhu.ledger.console.api.LedgerApi.Posting;
import io.github.essandhu.ledger.console.api.LedgerApiClient;

/**
 * The entry inspector — I1 made visible: the legs table closes with one total row PER
 * CURRENCY (balance is judged per currency, never across — multi-currency entries are legal
 * precisely because of that), each summing to a rendered zero. The totals are re-computed
 * here from the wire amounts in leg order, not trusted from anywhere.
 */
@Controller
class EntryController {

    private final LedgerApiClient api;

    EntryController(LedgerApiClient api) {
        this.api = api;
    }

    record LegRow(UUID postingId, UUID accountId, String amount, String side) {

        /** Locale-pinned CSS key — request-locale lowercasing would break it. */
        public String sideCss() {
            return side.toLowerCase(Locale.ROOT);
        }

        static LegRow from(Posting posting) {
            return new LegRow(posting.id(), posting.accountId(),
                    MoneyFormat.format(posting.amount()),
                    posting.amount().amount() >= 0 ? "DEBIT" : "CREDIT");
        }
    }

    /** One per currency; {@code balanced} is computed, and rendered honestly either way. */
    record TotalRow(String currency, String total, boolean balanced) {
    }

    @GetMapping("/entries/{id}")
    String entry(@PathVariable UUID id, Model model) {
        Entry entry = api.entry(id);

        // Leg order is preserved end to end (the core compares reversals positionally), so
        // summing in leg order retraces the server's own addExact path.
        Map<String, Long> totals = new LinkedHashMap<>();
        for (Posting posting : entry.postings()) {
            totals.merge(posting.amount().currency(), posting.amount().amount(), Math::addExact);
        }
        List<TotalRow> totalRows = totals.entrySet().stream()
                .map(sum -> new TotalRow(sum.getKey(),
                        MoneyFormat.format(sum.getValue(), sum.getKey()), sum.getValue() == 0))
                .toList();

        model.addAttribute("entry", entry);
        model.addAttribute("typeCss", entry.entryType().name().toLowerCase(Locale.ROOT));
        model.addAttribute("legs", entry.postings().stream().map(LegRow::from).toList());
        model.addAttribute("totals", totalRows);
        return "entry";
    }
}
