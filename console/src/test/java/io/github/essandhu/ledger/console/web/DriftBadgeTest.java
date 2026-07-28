package io.github.essandhu.ledger.console.web;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restclient.test.MockServerRestClientCustomizer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import io.github.essandhu.ledger.console.support.ConsoleSessions;
import io.github.essandhu.ledger.console.support.ConsoleWebTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The drift badge (M8-stretch): the last sweep's verdict, in the chrome of every page.
 *
 * <p>What is worth pinning here is not that a badge renders — it is the badge's
 * <em>honesty</em> and its <em>silence</em>. Honesty: it reports what the last sweep said,
 * with a drift count only when a finished sweep actually found drift, because nothing knows
 * whether an account has drifted until a sweep looks (ADR-0002). Silence: it is polled from
 * the page chrome every 15 seconds on behalf of a user who did not ask for it, so a failure
 * must leave the page exactly as it was — not paint an error into the topbar, and not blank
 * the previous verdict on one bad tick.
 */
@ConsoleWebTest
@DisplayName("Drift badge (M8-stretch): the last sweep's verdict, and silence when it cannot be read")
class DriftBadgeTest {

    private static final String RUN = "44444444-4444-4444-4444-444444444444";
    private static final String RUNS_URL = "http://localhost:8080/api/v1/reconciliation-runs";
    private static final String BADGE = "/reconciliation/drift-badge";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    MockServerRestClientCustomizer customizer;

    MockRestServiceServer server;

    @BeforeEach
    void bindServer() {
        server = customizer.getServer();
        server.reset();
    }

    private static String page(String content) {
        return """
                {"content": [%s], "page": 0, "size": 1, "totalElements": 4}""".formatted(content);
    }

    private static String run(String status, String counts) {
        return """
                {"id": "%s", "status": "%s", "startedAt": "2026-07-27T12:00:00Z", \
                "finishedAt": "2026-07-27T12:00:01Z", "triggeredBy": "ops"%s}"""
                .formatted(RUN, status, counts);
    }

    private void apiAnswers(String body) {
        server.expect(requestTo(startsWith(RUNS_URL)))
                // A page of ONE: the badge asks the newest-first listing (M8c) for exactly the
                // question it has. Asking for 20 and reading the first would work and would be
                // 20x the payload on a 15-second poll.
                .andExpect(queryParam("page", "0"))
                .andExpect(queryParam("size", "1"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private Document badgeFor(String body) throws Exception {
        apiAnswers(body);
        MvcTestResult result = mvc.get().uri(BADGE)
                .header("HX-Request", "true")
                .with(ConsoleSessions.user("ops", "LEDGER_READ", "LEDGER_ADMIN"))
                .exchange();
        assertThat(result).hasStatusOk();
        server.verify();
        return Jsoup.parse(result.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("a DRIFT verdict shows the count and links to the run that found it")
    void drift_shows_the_count_and_links_to_the_run() throws Exception {
        Document badge = badgeFor(page(run("DRIFT",
                ", \"accountsChecked\": 3, \"driftCount\": 1, \"currencyMismatchCount\": 0, "
                        + "\"postedAtMismatchCount\": 0, \"unbalancedCurrencyCount\": 0")));

        assertThat(badge.selectFirst(".status-badge").text()).isEqualTo("DRIFT");
        assertThat(badge.selectFirst(".status-badge").className()).contains("status-drift");
        assertThat(badge.selectFirst(".drift-count").text()).isEqualTo("1");
        assertThat(badge.selectFirst("a.drift-badge").attr("href"))
                .as("the badge is a way IN to the evidence, not just an indicator")
                .isEqualTo("/reconciliation/runs/" + RUN);
    }

    @Test
    @DisplayName("a CLEAN verdict shows no count — zero drifted accounts is not a number worth printing")
    void clean_shows_no_count() throws Exception {
        Document badge = badgeFor(page(run("CLEAN",
                ", \"accountsChecked\": 3, \"driftCount\": 0, \"currencyMismatchCount\": 0, "
                        + "\"postedAtMismatchCount\": 0, \"unbalancedCurrencyCount\": 0")));

        assertThat(badge.selectFirst(".status-badge").text()).isEqualTo("CLEAN");
        assertThat(badge.selectFirst(".drift-count")).isNull();
    }

    @Test
    @DisplayName("a RUNNING sweep counts nothing yet, so it shows the verdict alone — never a 0")
    void running_shows_no_count() throws Exception {
        // The counts are ABSENT on the wire for an unfinished run (the API's NON_NULL posture),
        // and "0" would read as "checked everything, found nothing" instead of "not done".
        Document badge = badgeFor(page("""
                {"id": "%s", "status": "RUNNING", "startedAt": "2026-07-27T12:00:00Z", \
                "triggeredBy": "ops"}""".formatted(RUN)));

        assertThat(badge.selectFirst(".status-badge").text()).isEqualTo("RUNNING");
        assertThat(badge.selectFirst(".drift-count")).isNull();
    }

    @Test
    @DisplayName("a stack with no sweeps yet renders nothing at all — the chrome stays quiet")
    void no_runs_renders_nothing() throws Exception {
        apiAnswers("""
                {"content": [], "page": 0, "size": 1, "totalElements": 0}""");

        MvcTestResult result = mvc.get().uri(BADGE)
                .with(ConsoleSessions.user("ops", "LEDGER_READ"))
                .exchange();

        assertThat(result).hasStatusOk();
        assertThat(result.getResponse().getContentAsString()).isBlank();
    }

    @Test
    @DisplayName("a refused poll is a non-event: 204, so the previous verdict stays on screen")
    void a_failed_poll_swaps_nothing() {
        // htmx swaps nothing on 204. Any 2xx with an error body would replace a real DRIFT
        // badge with an apology; a 4xx would be rendered by ConsoleErrorAdvice and paint a
        // problem document into the topbar of whatever page the user is actually reading.
        server.expect(requestTo(startsWith(RUNS_URL)))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("{\"title\": \"Forbidden\", \"status\": 403}"));

        assertThat(mvc.get().uri(BADGE)
                .header("HX-Request", "true")
                .with(ConsoleSessions.user("viewer", "LEDGER_READ")))
                .hasStatus(HttpStatus.NO_CONTENT);
        server.verify();
    }

    @Test
    @DisplayName("an unreachable ledger is silent too — the badge never speaks for the page")
    void an_unreachable_ledger_is_silent() {
        // ConsoleErrorAdvice renders a 503 "Ledger unreachable" page for this exception
        // everywhere else, which is right for a page the user asked for and wrong for a poll
        // they didn't. The controller-local handler is what draws that line.
        server.expect(requestTo(startsWith(RUNS_URL)))
                .andRespond(request -> {
                    throw new java.io.IOException("connection refused");
                });

        assertThat(mvc.get().uri(BADGE)
                .header("HX-Request", "true")
                .with(ConsoleSessions.user("ops", "LEDGER_READ")))
                .hasStatus(HttpStatus.NO_CONTENT);
    }

    @Test
    @DisplayName("the badge is in the chrome of every page, not only the reconciliation one")
    void the_slot_ships_with_the_topbar() throws Exception {
        server.expect(requestTo(startsWith("http://localhost:8080/api/v1/accounts")))
                .andRespond(withSuccess("""
                        {"content": [], "page": 0, "size": 20, "totalElements": 0}""",
                        MediaType.APPLICATION_JSON));

        MvcTestResult result = mvc.get().uri("/accounts")
                .with(ConsoleSessions.user("ops", "LEDGER_READ"))
                .exchange();

        Document page = Jsoup.parse(result.getResponse().getContentAsString());
        assertThat(page.selectFirst(".drift-badge-slot"))
                .as("an accounts page must carry the slot...")
                .isNotNull();
        assertThat(page.selectFirst(".drift-badge-slot").attr("hx-get"))
                .isEqualTo(BADGE);
        assertThat(page.selectFirst(".drift-badge-slot").attr("hx-trigger"))
                .as("...and poll it, without the page itself having fetched a verdict")
                .isEqualTo("load, every 15s");
        // The page render made exactly ONE call — the accounts one. A badge computed
        // server-side would have put a reconciliation read on this page's critical path.
        server.verify();
    }
}
