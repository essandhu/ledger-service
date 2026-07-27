package io.github.essandhu.ledger.console.web;

import java.io.IOException;

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
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/**
 * The error presentation contract (M8b): the API's problem+json surfaces distinguishably —
 * 401/403/404/400/422 each read differently, the console's response carries the API's own
 * status, and the detail text passes through instead of vanishing into "something went
 * wrong". Inside an htmx swap the same card arrives as a fragment.
 */
@ConsoleWebTest
@DisplayName("API error rendering (M8b): problem+json surfaced honestly, status mirrored")
class ApiErrorRenderingTest {

    private static final String ACCOUNT = "88888888-8888-8888-8888-888888888888";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    MockServerRestClientCustomizer customizer;

    MockRestServiceServer server;

    @BeforeEach
    void bindServer() throws Exception {
        server = customizer.getServer();
        server.reset();
    }

    private void apiAnswers(HttpStatus status, String problemJson) {
        server.expect(requestTo(startsWith("http://localhost:8080/api/v1/")))
                .andRespond(withStatus(status)
                        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body(problemJson));
    }

    private MvcTestResult getDetailPage() {
        return mvc.get().uri("/accounts/" + ACCOUNT)
                .with(ConsoleSessions.user("ops", "LEDGER_READ")).exchange();
    }

    private static Document parse(MvcTestResult result) throws Exception {
        return Jsoup.parse(result.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("API 404: the miss reads as a miss, detail passed through, status mirrored")
    void not_found_passes_through() throws Exception {
        apiAnswers(HttpStatus.NOT_FOUND, """
                {"type": "about:blank", "title": "Not Found", "status": 404, \
                "detail": "account %s does not exist"}""".formatted(ACCOUNT));

        MvcTestResult result = getDetailPage();

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
        Document page = parse(result);
        assertThat(page.selectFirst(".status-code").text()).isEqualTo("404");
        assertThat(page.selectFirst("h1").text()).isEqualTo("Nothing at this address");
        assertThat(page.selectFirst(".error-detail").text())
                .contains(ACCOUNT + " does not exist");
    }

    @Test
    @DisplayName("API 403: the role matrix said no — the core's exact refusal text renders")
    void forbidden_reads_as_roles() throws Exception {
        apiAnswers(HttpStatus.FORBIDDEN, """
                {"type": "about:blank", "title": "Forbidden", "status": 403, \
                "detail": "The authenticated principal does not hold the role this operation requires."}""");

        MvcTestResult result = getDetailPage();

        assertThat(result).hasStatus(HttpStatus.FORBIDDEN);
        Document page = parse(result);
        assertThat(page.selectFirst("h1").text()).contains("Not permitted");
        assertThat(page.selectFirst(".error-detail").text()).contains("does not hold the role");
    }

    @Test
    @DisplayName("API 401: the relayed token was rejected — named as a session problem")
    void unauthorized_reads_as_session() throws Exception {
        apiAnswers(HttpStatus.UNAUTHORIZED, """
                {"type": "about:blank", "title": "Unauthorized", "status": 401, \
                "detail": "Authentication via a bearer JWT from the configured issuer is required."}""");

        MvcTestResult result = getDetailPage();

        assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(parse(result).selectFirst("h1").text())
                .isEqualTo("The ledger rejected the session token");
    }

    @Test
    @DisplayName("API 400 (invalid cursor): the detail names the actual complaint")
    void bad_request_detail_surfaces() throws Exception {
        apiAnswers(HttpStatus.BAD_REQUEST, """
                {"type": "about:blank", "title": "Bad Request", "status": 400, \
                "detail": "cursor is not a token this service issued"}""");

        MvcTestResult result = mvc.get()
                .uri("/accounts/" + ACCOUNT + "/statement?cursor=forged")
                .header("HX-Request", "true")
                .with(ConsoleSessions.user("ops", "LEDGER_READ")).exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        String body = result.getResponse().getContentAsString();
        // The htmx path gets the fragment: the card, not a nested full page.
        assertThat(body).doesNotContain("<html");
        Document fragment = Jsoup.parse(body);
        assertThat(fragment.selectFirst(".error-card")).isNotNull();
        assertThat(fragment.selectFirst(".error-detail").text())
                .isEqualTo("cursor is not a token this service issued");
    }

    @Test
    @DisplayName("API 422: a typed ledger rule shows its problem type URI")
    void unprocessable_shows_typed_slug() throws Exception {
        apiAnswers(HttpStatus.UNPROCESSABLE_CONTENT, """
                {"type": "https://essandhu.github.io/ledger/problems/unbalanced-entry", \
                "title": "Unbalanced entry", "status": 422, \
                "detail": "entry does not net to zero in EUR"}""");

        MvcTestResult result = getDetailPage();

        assertThat(result).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        Document page = parse(result);
        assertThat(page.selectFirst("h1").text()).isEqualTo("Rejected by a ledger rule");
        assertThat(page.selectFirst(".error-type").text())
                .isEqualTo("https://essandhu.github.io/ledger/problems/unbalanced-entry");
    }

    @Test
    @DisplayName("ledger unreachable: 503 with the honest remedy — and no internal URL leaked")
    void unreachable_reads_as_down() throws Exception {
        server.expect(requestTo(startsWith("http://localhost:8080/api/v1/")))
                .andRespond(request -> {
                    throw new IOException("Connection refused: localhost:8080");
                });

        MvcTestResult result = getDetailPage();

        assertThat(result).hasStatus(HttpStatus.SERVICE_UNAVAILABLE);
        Document page = parse(result);
        assertThat(page.selectFirst("h1").text()).isEqualTo("Ledger unreachable");
        assertThat(page.selectFirst(".error-detail").text())
                .contains("docker compose up")
                // The cause names internal topology; it belongs in the log, not the page.
                .doesNotContain("localhost:8080", "Connection refused");
    }

    @Test
    @DisplayName("API 500: a server-side failure reads as the ledger's, detail passed through")
    void server_error_reads_as_ledger_failure() throws Exception {
        apiAnswers(HttpStatus.INTERNAL_SERVER_ERROR, """
                {"type": "about:blank", "title": "Internal Server Error", "status": 500, \
                "detail": "unexpected condition"}""");

        MvcTestResult result = getDetailPage();

        assertThat(result).hasStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        Document page = parse(result);
        assertThat(page.selectFirst("h1").text()).isEqualTo("The ledger failed");
        assertThat(page.selectFirst(".error-detail").text()).isEqualTo("unexpected condition");
    }

    @Test
    @DisplayName("a non-problem body (proxy HTML) degrades to the status text, never a blank card")
    void non_problem_body_falls_back_to_status_text() throws Exception {
        server.expect(requestTo(startsWith("http://localhost:8080/api/v1/")))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY)
                        .contentType(MediaType.TEXT_HTML)
                        .body("<html><body>Bad Gateway</body></html>"));

        MvcTestResult result = getDetailPage();

        assertThat(result).hasStatus(HttpStatus.BAD_GATEWAY);
        Document page = parse(result);
        assertThat(page.selectFirst("h1").text()).isEqualTo("The ledger failed");
        assertThat(page.selectFirst(".error-title").text()).isEqualTo("Bad Gateway");
        assertThat(page.select(".error-detail")).isEmpty();
    }

    @Test
    @DisplayName("a failure mid load-more arrives row-shaped — table-legal for the sentinel swap")
    void sentinel_target_gets_the_row_shaped_fragment() throws Exception {
        apiAnswers(HttpStatus.SERVICE_UNAVAILABLE, """
                {"type": "about:blank", "title": "Service Unavailable", "status": 503, \
                "detail": "down"}""");

        MvcTestResult result = mvc.get()
                .uri("/accounts/" + ACCOUNT + "/statement?cursor=CUR")
                .header("HX-Request", "true")
                .header("HX-Target", "more-" + ACCOUNT)
                .with(ConsoleSessions.user("ops", "LEDGER_READ")).exchange();

        assertThat(result).hasStatus(HttpStatus.SERVICE_UNAVAILABLE);
        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("<html");
        Document fragment = Jsoup.parse("<table>" + body + "</table>");
        org.jsoup.nodes.Element row = fragment.selectFirst("tr.error-row");
        assertThat(row).isNotNull();
        assertThat(row.selectFirst("td").attr("colspan")).isEqualTo("4");
        assertThat(row.selectFirst(".error-card")).isNotNull();
    }
}
