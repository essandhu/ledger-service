package io.github.essandhu.ledger.console.web;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.ClientAuthorizationException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.client.RestClientException;

import io.github.essandhu.ledger.console.api.LedgerApi.Run;
import io.github.essandhu.ledger.console.api.LedgerApi.RunPage;
import io.github.essandhu.ledger.console.api.LedgerApiClient;

/**
 * The drift badge (M8-stretch): the verdict of the most recent reconciliation sweep, polled
 * into the page chrome from wherever the user happens to be. It answers the question the
 * console could previously only answer by navigating away from what you were doing — "is the
 * ledger currently believed to be intact?" — and it answers it honestly, as
 * <em>what the last sweep said</em> rather than as a live claim about the data. Nothing knows
 * whether an account has drifted until a sweep looks (ADR-0002); a badge that implied
 * otherwise would be the one dishonest pixel in the console.
 *
 * <p>Polled rather than rendered with the page, deliberately. The badge sits in a fragment
 * every page includes, so computing it server-side would add an API call to the critical path
 * of pages that have nothing to do with reconciliation — and would let a slow or failing
 * reconciliation read break an account listing. Asynchronous means the worst case is a badge
 * that says nothing.
 *
 * <p>Its own controller, not a method on {@link ReconciliationController}, because of the
 * handler below: an {@code @ExceptionHandler} is scoped to its controller, and silencing
 * failures is right for ambient chrome and WRONG for the pages a user asked for. Sharing a
 * class would extend this silence to the run history and findings, where
 * {@code ConsoleErrorAdvice}'s honest problem rendering is the whole contract.
 */
@Controller
class DriftBadgeController {

    private static final Logger log = LoggerFactory.getLogger(DriftBadgeController.class);

    private final LedgerApiClient api;

    DriftBadgeController(LedgerApiClient api) {
        this.api = api;
    }

    /**
     * A sweep verdict reduced to what the chrome renders. {@code driftCount} stays boxed and
     * is absent on a RUNNING or FAILED run — the badge shows the verdict alone there, because
     * a run that did not finish counted nothing, and a "0" would read as "checked everything,
     * found nothing" (the same distinction the run table draws with an em dash).
     */
    record BadgeView(UUID runId, String status, String statusCss, Long driftCount,
            Instant startedAt) {

        static BadgeView from(Run run) {
            return new BadgeView(run.id(), run.status().name(),
                    // Locale.ROOT, as everywhere: request-locale lowercasing breaks CSS keys.
                    run.status().name().toLowerCase(Locale.ROOT),
                    run.driftCount() != null && run.driftCount() > 0 ? run.driftCount() : null,
                    run.startedAt());
        }
    }

    /**
     * The newest run, which the API's run listing puts first — a page of ONE, because that is
     * the entire question (M8c made this listing descending for exactly this kind of read).
     */
    @GetMapping("/reconciliation/drift-badge")
    String badge(Model model) {
        RunPage page = api.runs(0, 1);
        List<Run> content = page.content();
        model.addAttribute("badge", content.isEmpty() ? null : BadgeView.from(content.getFirst()));
        return "fragments :: driftBadge";
    }

    /**
     * A failed badge poll is a non-event: 204, so htmx swaps nothing and whatever the badge
     * last said stays on screen rather than being replaced by an error — or worse, blanked
     * every 15 seconds by a transient blip.
     *
     * <p>This is the one place the console deliberately does NOT surface the ledger's problem
     * document, and it is scoped to this controller for that reason. The user did not ask for
     * this request; something on their behalf did. If the ledger is genuinely unreachable or
     * refusing, the next page they actually ask for will say so honestly, in the place they
     * are looking. Logged at debug, not warn: a poll every 15 seconds against a stack that is
     * down would otherwise fill the log with one line per tick.
     */
    @ExceptionHandler({RestClientException.class, ClientAuthorizationException.class})
    ResponseEntity<Void> badgeStaysQuiet(Exception failure) {
        log.debug("drift badge poll failed; leaving the previous verdict on screen", failure);
        return ResponseEntity.noContent().build();
    }
}
