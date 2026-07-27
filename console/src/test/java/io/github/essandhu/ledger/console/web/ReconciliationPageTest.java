package io.github.essandhu.ledger.console.web;

import java.util.stream.IntStream;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Reconciliation (M8c): the run history newest-first as the API orders it, the findings
 * drill-down that renders I15 as snapshot vs computed vs delta, and the ops-only trigger —
 * hidden from a viewer, refused by the LEDGER (not by a second role matrix here) when a viewer
 * posts anyway.
 *
 * <p>The demo's exact figures are the fixtures: one drifted account, snapshot 12352 against a
 * computed 12345, delta 7 — the superuser {@code +7} corruption ADR-0002 uses to prove the
 * sweep can see what no API write could have caused.
 */
@ConsoleWebTest
@DisplayName("Reconciliation (M8c): run history, findings drill-down, ops-only trigger")
class ReconciliationPageTest {

    private static final String RUN = "44444444-4444-4444-4444-444444444444";
    private static final String ACCOUNT = "33333333-3333-3333-3333-333333333333";
    private static final String RUNS_URL = "http://localhost:8080/api/v1/reconciliation-runs";

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

    /** A finished run; {@code counts} is the five-count tail, absent on RUNNING/FAILED rows. */
    private static String runJson(String id, String status, String counts) {
        return """
                {"id": "%s", "status": "%s", "startedAt": "2026-07-27T12:00:00Z", \
                "finishedAt": "2026-07-27T12:00:01Z", "triggeredBy": "ops"%s}"""
                .formatted(id, status, counts);
    }

    private static String driftCounts() {
        return """
                , "accountsChecked": 3, "driftCount": 1, "currencyMismatchCount": 0, \
                "postedAtMismatchCount": 0, "unbalancedCurrencyCount": 0""";
    }

    private static String runPageJson(String content, int page, long total) {
        return "{\"content\": [%s], \"page\": %d, \"size\": 20, \"totalElements\": %d}"
                .formatted(content, page, total);
    }

    /** The demo's finding: snapshot 12352 vs computed 12345, delta 7, counts in agreement. */
    private static String findingJson(long snapshotBalance, long snapshotCount,
            long computedBalance, long computedCount) {
        return """
                {"id": "55555555-5555-5555-5555-555555555555", "accountId": "%s", \
                "snapshotBalance": %d, "snapshotCount": %d, "computedBalance": %d, \
                "computedCount": %d, "delta": %d}"""
                .formatted(ACCOUNT, snapshotBalance, snapshotCount, computedBalance,
                        computedCount, snapshotBalance - computedBalance);
    }

    private static String findingsPageJson(String content, long total) {
        return "{\"content\": [%s], \"page\": 0, \"size\": 20, \"totalElements\": %d}"
                .formatted(content, total);
    }

    private void expectRunListing(String body) {
        server.expect(requestTo(startsWith(RUNS_URL)))
                .andExpect(queryParam("page", "0"))
                .andExpect(queryParam("size", "20"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private void expectRunAndFindings(String runBody, String findingsBody) {
        server.expect(requestTo(RUNS_URL + "/" + RUN))
                .andRespond(withSuccess(runBody, MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith(RUNS_URL + "/" + RUN + "/findings")))
                .andRespond(withSuccess(findingsBody, MediaType.APPLICATION_JSON));
    }

    private Document getPage(String uri, String... roles) throws Exception {
        MvcTestResult result = mvc.get().uri(uri)
                .with(ConsoleSessions.user("ops", roles)).exchange();
        assertThat(result).hasStatusOk();
        return Jsoup.parse(result.getResponse().getContentAsString());
    }

    @Nested
    @DisplayName("Run history")
    class RunHistory {

        @Test
        @DisplayName("rows render in the API's order — newest first, never re-sorted here")
        void rows_render_in_api_order() throws Exception {
            expectRunListing(runPageJson(
                    runJson(RUN, "DRIFT", driftCounts()) + ", "
                            + runJson("11111111-1111-1111-1111-111111111111", "CLEAN", """
                                    , "accountsChecked": 3, "driftCount": 0, \
                                    "currencyMismatchCount": 0, "postedAtMismatchCount": 0, \
                                    "unbalancedCurrencyCount": 0"""),
                    0, 2));

            Document page = getPage("/reconciliation", "LEDGER_READ");

            assertThat(page.select("tbody .status-badge").eachText())
                    .containsExactly("DRIFT", "CLEAN");
            assertThat(page.selectFirst("tbody .status-badge").hasClass("status-drift")).isTrue();
            assertThat(page.selectFirst("tbody tr a").attr("href"))
                    .isEqualTo("/reconciliation/runs/" + RUN);
            server.verify();
        }

        @Test
        @DisplayName("a RUNNING row shows an em dash for its counts, never a zero that would read 'all clear'")
        void running_row_shows_absent_counts() throws Exception {
            expectRunListing(runPageJson("""
                    {"id": "%s", "status": "RUNNING", "startedAt": "2026-07-27T12:00:00Z", \
                    "triggeredBy": "scheduler"}""".formatted(RUN), 0, 1));

            Document page = getPage("/reconciliation", "LEDGER_READ");

            assertThat(page.select("tbody td.num").eachText()).containsExactly("—", "—");
            assertThat(page.selectFirst("tbody .status-badge").hasClass("status-running")).isTrue();
            assertThat(page.selectFirst("tbody tr").text()).contains("still running");
        }

        @Test
        @DisplayName("an empty history says so, and points at the two ways a run comes to exist")
        void empty_history_explains_itself() throws Exception {
            expectRunListing(runPageJson("", 0, 0));

            Document page = getPage("/reconciliation", "LEDGER_READ");

            assertThat(page.selectFirst("td.empty").text()).contains("No sweeps recorded yet");
            assertThat(page.select("tbody tr")).hasSize(1);
            // The pager's FALSE state, which the page-1 cell below can never show: with nothing
            // to page through, neither link renders — and the count still reads honestly.
            assertThat(page.select(".pager a")).isEmpty();
            assertThat(page.selectFirst(".pager-status").text()).isEqualTo("Page 1 of 1 · 0 runs");
        }

        @Test
        @DisplayName("the pager walks the history: Older advances the page, Newer appears only past page 0")
        void pager_links_carry_the_page() throws Exception {
            String rows = IntStream.range(0, 20)
                    .mapToObj(i -> runJson("1111111%d-1111-1111-1111-111111111111".formatted(i % 10),
                            "CLEAN", driftCounts().replace("\"driftCount\": 1", "\"driftCount\": 0")))
                    .reduce((a, b) -> a + ", " + b).orElseThrow();
            server.expect(requestTo(startsWith(RUNS_URL)))
                    .andExpect(queryParam("page", "1"))
                    .andRespond(withSuccess(runPageJson(rows, 1, 45),
                            MediaType.APPLICATION_JSON));

            Document page = getPage("/reconciliation?page=1", "LEDGER_READ");

            assertThat(page.selectFirst(".pager-status").text())
                    .isEqualTo("Page 2 of 3 · 45 runs");
            assertThat(page.select(".pager a").eachAttr("href"))
                    .containsExactly("/reconciliation?page=0", "/reconciliation?page=2");
        }
    }

    @Nested
    @DisplayName("Findings drill-down")
    class Findings {

        @Test
        @DisplayName("the drift reads snapshot 12352 vs computed 12345, delta 7 — I15, rendered")
        void finding_renders_the_pair_and_the_delta() throws Exception {
            expectRunAndFindings(runJson(RUN, "DRIFT", driftCounts()),
                    findingsPageJson(findingJson(12352, 1, 12345, 1), 1));

            Document page = getPage("/reconciliation/runs/" + RUN, "LEDGER_READ");

            assertThat(page.select("tbody td.num").eachText())
                    .containsExactly("12352", "1", "12345", "1", "7");
            assertThat(page.selectFirst("td.delta").text()).isEqualTo("7");
            // The negative half of the watermark marker: counts AGREE here (1 and 1), so no
            // cell may claim otherwise. Without this, a countDrift stuck at true is invisible —
            // `drifted` lands on cells that already carry `num mono`, so it changes no other
            // selector or text in this test.
            assertThat(page.select("td.drifted")).isEmpty();
            // Figures stay bare minor units: no currency on a finding, so no exponent applied.
            assertThat(page.selectFirst("td.delta").text()).doesNotContain(".", "EUR");
            assertThat(page.selectFirst("tbody tr a").attr("href"))
                    .isEqualTo("/accounts/" + ACCOUNT);
            server.verify();
        }

        @Test
        @DisplayName("the five counts render on the card; the DRIFT verdict badges red")
        void run_card_renders_the_counts() throws Exception {
            expectRunAndFindings(runJson(RUN, "DRIFT", driftCounts()),
                    findingsPageJson(findingJson(12352, 1, 12345, 1), 1));

            Document page = getPage("/reconciliation/runs/" + RUN, "LEDGER_READ");

            assertThat(page.select(".run-counts dd").eachText())
                    .containsExactly("3", "1", "0", "0", "0");
            assertThat(page.selectFirst(".identity-body .status-badge").hasClass("status-drift"))
                    .isTrue();
        }

        @Test
        @DisplayName("a count-only drift is marked: equal balances, diverged watermark (ADR-0002's compensating case)")
        void count_only_drift_marks_the_watermark() throws Exception {
            expectRunAndFindings(runJson(RUN, "DRIFT", driftCounts()),
                    findingsPageJson(findingJson(12345, 2, 12345, 1), 1));

            Document page = getPage("/reconciliation/runs/" + RUN, "LEDGER_READ");

            assertThat(page.selectFirst("td.delta").text()).isEqualTo("0");
            assertThat(page.select("td.drifted").eachText()).containsExactly("2", "1");
        }

        @Test
        @DisplayName("a CLEAN run has no findings and no counts panel is suppressed — the empty state explains why")
        void clean_run_renders_the_empty_state() throws Exception {
            expectRunAndFindings(runJson(RUN, "CLEAN",
                    driftCounts().replace("\"driftCount\": 1", "\"driftCount\": 0")),
                    findingsPageJson("", 0));

            Document page = getPage("/reconciliation/runs/" + RUN, "LEDGER_READ");

            assertThat(page.selectFirst("td.empty").text())
                    .contains("every account's", "matched the sum of its postings");
            assertThat(page.select(".run-counts dd")).hasSize(5);
        }

        @Test
        @DisplayName("an all-zero CLEAN run still shows its counts — a zero count is a fact, not an absence")
        void zero_counts_are_rendered_not_hidden() throws Exception {
            // The Thymeleaf trap: a bare th:if on a Number is FALSE at zero, so a sweep of an
            // empty ledger (accountsChecked 0, a perfectly good CLEAN verdict) would render as
            // "this run reports no counts" — claiming it never reached a verdict. Only an
            // explicit != null distinguishes "counted nothing" from "has nothing to report".
            expectRunAndFindings("""
                    {"id": "%s", "status": "CLEAN", "startedAt": "2026-07-27T12:00:00Z", \
                    "finishedAt": "2026-07-27T12:00:01Z", "triggeredBy": "scheduler", \
                    "accountsChecked": 0, "driftCount": 0, "currencyMismatchCount": 0, \
                    "postedAtMismatchCount": 0, "unbalancedCurrencyCount": 0}""".formatted(RUN),
                    findingsPageJson("", 0));

            Document page = getPage("/reconciliation/runs/" + RUN, "LEDGER_READ");

            assertThat(page.select(".run-counts dd").eachText())
                    .containsExactly("0", "0", "0", "0", "0");
            assertThat(page.select(".identity-body .no-roles")).isEmpty();
        }

        @Test
        @DisplayName("findings page through: the page reaches the API and both pager links carry the run id")
        void findings_paging_forwards_and_links() throws Exception {
            server.expect(requestTo(RUNS_URL + "/" + RUN))
                    .andRespond(withSuccess(runJson(RUN, "DRIFT", driftCounts()),
                            MediaType.APPLICATION_JSON));
            server.expect(requestTo(startsWith(RUNS_URL + "/" + RUN + "/findings")))
                    .andExpect(queryParam("page", "1"))
                    .andExpect(queryParam("size", "20"))
                    .andRespond(withSuccess(
                            "{\"content\": [%s], \"page\": 1, \"size\": 20, \"totalElements\": 45}"
                                    .formatted(findingJson(12352, 1, 12345, 1)),
                            MediaType.APPLICATION_JSON));

            Document page = getPage("/reconciliation/runs/" + RUN + "?page=1", "LEDGER_READ");

            assertThat(page.selectFirst(".pager-status").text())
                    .isEqualTo("Page 2 of 3 · 45 findings");
            // Both links must carry the run id, or paging navigates away from the run.
            assertThat(page.select(".pager a").eachAttr("href")).containsExactly(
                    "/reconciliation/runs/" + RUN + "?page=0",
                    "/reconciliation/runs/" + RUN + "?page=2");
            server.verify();
        }

        @Test
        @DisplayName("a FAILED run reports no counts at all, and says that rather than printing zeroes")
        void failed_run_reports_no_counts() throws Exception {
            expectRunAndFindings("""
                    {"id": "%s", "status": "FAILED", "startedAt": "2026-07-27T12:00:00Z", \
                    "finishedAt": "2026-07-27T12:00:05Z", "triggeredBy": "ops"}""".formatted(RUN),
                    findingsPageJson("", 0));

            Document page = getPage("/reconciliation/runs/" + RUN, "LEDGER_READ");

            assertThat(page.select(".run-counts")).isEmpty();
            assertThat(page.selectFirst(".identity-body .no-roles").text())
                    .contains("records them only once it reaches a verdict");
            assertThat(page.selectFirst(".identity-body .status-badge").hasClass("status-failed"))
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("Ops-only trigger")
    class Trigger {

        @Test
        @DisplayName("ops sees the button, carrying the htmx confirm and the CSRF field Thymeleaf adds")
        void ops_sees_the_trigger() throws Exception {
            expectRunListing(runPageJson("", 0, 0));

            Document page = getPage("/reconciliation", "LEDGER_READ", "LEDGER_ADMIN");

            org.jsoup.nodes.Element form = page.selectFirst("form.trigger-form");
            assertThat(form).isNotNull();
            assertThat(form.attr("hx-post")).isEqualTo("/reconciliation/runs");
            assertThat(form.attr("hx-target")).isEqualTo("#trigger-problem");
            assertThat(form.attr("hx-confirm")).contains("Run a reconciliation sweep now?");
            // The JS-off path is a real form post, so it needs the token htmx would also send.
            assertThat(form.selectFirst("input[name=_csrf]")).isNotNull();
            assertThat(page.selectFirst("#trigger-problem").text()).isEmpty();
        }

        @Test
        @DisplayName("a viewer never sees the button — sec:authorize, the role-aware rendering cell")
        void viewer_does_not_see_the_trigger() throws Exception {
            expectRunListing(runPageJson("", 0, 0));

            Document page = getPage("/reconciliation", "LEDGER_READ");

            assertThat(page.select("form.trigger-form")).isEmpty();
            assertThat(page.select("button.trigger")).isEmpty();
            // The slot still exists — it is page chrome, not part of the privileged control.
            assertThat(page.selectFirst("#trigger-problem")).isNotNull();
        }

        @Test
        @DisplayName("htmx trigger: 204 + HX-Redirect to the run it created, so nothing is swapped")
        void htmx_trigger_redirects_to_the_new_run() {
            server.expect(requestTo(RUNS_URL))
                    .andExpect(method(org.springframework.http.HttpMethod.POST))
                    .andRespond(withStatus(HttpStatus.CREATED)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(runJson(RUN, "DRIFT", driftCounts())));

            assertThat(mvc.post().uri("/reconciliation/runs")
                    .header("HX-Request", "true")
                    .with(ConsoleSessions.user("ops", "LEDGER_READ", "LEDGER_ADMIN"))
                    .with(csrf()))
                    .hasStatus(HttpStatus.NO_CONTENT)
                    .hasHeader("HX-Redirect", "/reconciliation/runs/" + RUN);
            server.verify();
        }

        @Test
        @DisplayName("JavaScript off: the same POST is a plain 303 to the same run")
        void plain_form_post_redirects() {
            server.expect(requestTo(RUNS_URL))
                    .andExpect(method(org.springframework.http.HttpMethod.POST))
                    .andRespond(withStatus(HttpStatus.CREATED)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(runJson(RUN, "CLEAN",
                                    driftCounts().replace("\"driftCount\": 1",
                                            "\"driftCount\": 0"))));

            assertThat(mvc.post().uri("/reconciliation/runs")
                    .with(ConsoleSessions.user("ops", "LEDGER_READ", "LEDGER_ADMIN"))
                    .with(csrf()))
                    .hasStatus(HttpStatus.SEE_OTHER)
                    .hasRedirectedUrl("/reconciliation/runs/" + RUN);
        }

        @Test
        @DisplayName("a viewer who posts anyway is refused by the LEDGER — 403 rendered as the problem it is")
        void viewer_post_is_refused_by_the_api() throws Exception {
            // The console does NOT re-check the role: the request rides the viewer's own token
            // and the API answers 403, which is the single authority on the role matrix.
            server.expect(requestTo(RUNS_URL))
                    .andExpect(method(org.springframework.http.HttpMethod.POST))
                    .andRespond(withStatus(HttpStatus.FORBIDDEN)
                            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                            .body("""
                                    {"type": "about:blank", "title": "Forbidden", "status": 403, \
                                    "detail": "Access Denied"}"""));

            MvcTestResult result = mvc.post().uri("/reconciliation/runs")
                    .header("HX-Request", "true")
                    .header("HX-Target", "trigger-problem")
                    .with(ConsoleSessions.user("viewer", "LEDGER_READ"))
                    .with(csrf())
                    .exchange();

            assertThat(result).hasStatus(HttpStatus.FORBIDDEN);
            Document fragment = Jsoup.parse(result.getResponse().getContentAsString());
            assertThat(fragment.selectFirst(".status-code").text()).isEqualTo("403");
            assertThat(fragment.selectFirst(".error-card h1").text())
                    .isEqualTo("Not permitted for this account's roles");
            assertThat(result.getResponse().getContentAsString()).doesNotContain("<html");
            server.verify();
        }

        @Test
        @DisplayName("a trigger without a CSRF token is refused before it reaches the ledger")
        void trigger_without_csrf_is_refused() {
            assertThat(mvc.post().uri("/reconciliation/runs")
                    .with(ConsoleSessions.user("ops", "LEDGER_READ", "LEDGER_ADMIN")))
                    .hasStatus(HttpStatus.FORBIDDEN);
            // Nothing was expected of the mock server: the sweep never started.
            server.verify();
        }
    }
}
