package io.github.essandhu.ledger.console.web;

import java.net.URI;
import java.util.Locale;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import io.github.essandhu.ledger.console.api.LedgerApi.Finding;
import io.github.essandhu.ledger.console.api.LedgerApi.FindingsPage;
import io.github.essandhu.ledger.console.api.LedgerApi.Run;
import io.github.essandhu.ledger.console.api.LedgerApi.RunPage;
import io.github.essandhu.ledger.console.api.LedgerApiClient;

/**
 * The reconciliation story, which is the whole point of the console being more than a viewer:
 * the run history (newest first, as the API orders it), a run's findings drilled down to
 * snapshot vs computed vs delta — I15 made visible — and the one permitted action, triggering
 * a sweep.
 *
 * <p>The trigger is authorized ONCE, by the ledger: the button is hidden from principals
 * without {@code LEDGER_ADMIN} (template-side, {@code sec:authorize}), but a hand-rolled POST
 * from a viewer is not blocked here — it rides the user's own token to the API and comes back
 * 403, which {@code ConsoleErrorAdvice} renders honestly. Re-checking the role in the console
 * would fork the role matrix into two places that can disagree; hiding-plus-honest-403 keeps
 * the API the single authority (the {@code LedgerApiClient} contract).
 *
 * <p>Findings page with plain offset links rather than the statement's htmx load-more: the
 * API's findings surface IS offset-paged (a bounded read-your-report listing), where the
 * statement is keyset — the pagination the page shows is the pagination the API has.
 */
@Controller
class ReconciliationController {

    static final int RUN_PAGE_SIZE = 20;
    static final int FINDING_PAGE_SIZE = 20;

    private final LedgerApiClient api;

    ReconciliationController(LedgerApiClient api) {
        this.api = api;
    }

    /** A run plus the one thing the template cannot compute safely: its locale-pinned CSS key. */
    record RunView(Run run, String statusCss) {

        static RunView from(Run run) {
            // Locale.ROOT, as everywhere: request-locale lowercasing breaks CSS keys.
            return new RunView(run, run.status().name().toLowerCase(Locale.ROOT));
        }
    }

    /**
     * One drifted account, presentation-ready. Figures stay BARE minor units — a finding
     * carries no currency (the API's shape), so there is no exponent the console may honestly
     * apply. {@code countDrift} surfaces ADR-0002's subtle case: a compensating corruption can
     * leave the balances equal while the posting watermark diverges.
     */
    record FindingRow(UUID accountId, long snapshotBalance, long snapshotCount,
            long computedBalance, long computedCount, long delta, boolean countDrift) {

        static FindingRow from(Finding finding) {
            return new FindingRow(finding.accountId(), finding.snapshotBalance(),
                    finding.snapshotCount(), finding.computedBalance(), finding.computedCount(),
                    finding.delta(), finding.snapshotCount() != finding.computedCount());
        }
    }

    @GetMapping("/reconciliation")
    String runs(@RequestParam(defaultValue = "0") int page, Model model) {
        int safePage = Math.max(0, page);
        RunPage result = api.runs(safePage, RUN_PAGE_SIZE);

        model.addAttribute("rows", result.content().stream().map(RunView::from).toList());
        model.addAttribute("page", result.page());
        model.addAttribute("totalElements", result.totalElements());
        model.addAttribute("totalPages", result.totalPages());
        model.addAttribute("hasPrevious", result.page() > 0);
        model.addAttribute("hasNext", result.page() + 1 < result.totalPages());
        return "reconciliation";
    }

    @GetMapping("/reconciliation/runs/{id}")
    String run(@PathVariable UUID id, @RequestParam(defaultValue = "0") int page, Model model) {
        int safePage = Math.max(0, page);
        model.addAttribute("run", RunView.from(api.run(id)));

        FindingsPage findings = api.findings(id, safePage, FINDING_PAGE_SIZE);
        model.addAttribute("runId", id);
        model.addAttribute("findings",
                findings.content().stream().map(FindingRow::from).toList());
        model.addAttribute("page", findings.page());
        model.addAttribute("totalElements", findings.totalElements());
        model.addAttribute("totalPages", findings.totalPages());
        model.addAttribute("hasPrevious", findings.page() > 0);
        model.addAttribute("hasNext", findings.page() + 1 < findings.totalPages());
        return "run";
    }

    /**
     * Start a sweep, then send the browser to the run it created — where the verdict and any
     * findings already are. Both callers land in the same place: htmx gets {@code HX-Redirect}
     * (it would otherwise follow the 303 at the XHR level and hand a whole page to a swap
     * target), a JavaScript-off form post gets the plain 303. The confirm dialog is htmx's
     * {@code hx-confirm} — an inline {@code onsubmit} handler would need a CSP the console
     * deliberately does not grant.
     */
    @PostMapping("/reconciliation/runs")
    ResponseEntity<Void> trigger(
            @RequestHeader(value = "HX-Request", required = false) String hxRequest) {
        String location = "/reconciliation/runs/" + api.triggerRun().id();
        HttpHeaders headers = new HttpHeaders();
        if ("true".equals(hxRequest)) {
            headers.add("HX-Redirect", location);
            // 204: htmx swaps nothing and navigates instead — the success path leaves the
            // error slot below the button untouched.
            return new ResponseEntity<>(headers, HttpStatus.NO_CONTENT);
        }
        headers.setLocation(URI.create(location));
        return new ResponseEntity<>(headers, HttpStatus.SEE_OTHER);
    }
}
