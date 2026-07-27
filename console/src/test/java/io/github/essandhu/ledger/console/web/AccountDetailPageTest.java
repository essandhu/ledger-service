package io.github.essandhu.ledger.console.web;

import java.util.UUID;
import java.util.stream.IntStream;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restclient.test.MockServerRestClientCustomizer;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import io.github.essandhu.ledger.console.support.ConsoleSessions;
import io.github.essandhu.ledger.console.support.ConsoleWebTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Account detail: the natural-balance headline (the sign trap, pinned — the demo's LIABILITY
 * target at raw −12345 shows +123.45 EUR), the as-of picker, and the statement with the
 * htmx load-more sentinel whose visibility keys off page fullness, never off nextCursor.
 */
@ConsoleWebTest
@DisplayName("Account detail (M8b): natural balance, as-of picker, statement + load more")
class AccountDetailPageTest {

    private static final String ACCOUNT = "33333333-3333-3333-3333-333333333333";

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

    private static final String ACCOUNT_JSON = """
            {"id": "%s", "name": "demo-customer", "currency": "EUR", "type": "LIABILITY", \
            "status": "ACTIVE", "allowNegative": false, "createdAt": "2026-07-27T10:00:00Z", \
            "updatedAt": "2026-07-27T11:00:00Z"}""".formatted(ACCOUNT);

    /** The demo's exact figures: LIABILITY, raw −12345 debit-positive, natural +12345. */
    private static String balanceJson(String asOfField) {
        return """
                {"accountId": "%s", "type": "LIABILITY", \
                "balance": {"amount": 12345, "currency": "EUR"}, \
                "rawBalance": {"amount": -12345, "currency": "EUR"}, \
                "postingCount": 2%s}""".formatted(ACCOUNT, asOfField);
    }

    private static String lineJson(int index, long amount) {
        return """
                {"id": "%s", "entryId": "%s", \
                "amount": {"amount": %d, "currency": "EUR"}, \
                "postedAt": "2026-07-27T10:00:%02dZ"}"""
                .formatted(new UUID(1, index), new UUID(2, index), amount, index % 60);
    }

    private static String statementJson(int lines, String nextCursor) {
        String content = IntStream.range(0, lines)
                .mapToObj(i -> lineJson(i, i % 2 == 0 ? 500 : -500))
                .reduce((a, b) -> a + ", " + b).orElse("");
        return "{\"content\": [%s], \"nextCursor\": %s}"
                .formatted(content, nextCursor == null ? "null" : "\"" + nextCursor + "\"");
    }

    private void expectAccountAndBalance(String balanceBody) {
        server.expect(requestTo("http://localhost:8080/api/v1/accounts/" + ACCOUNT))
                .andRespond(withSuccess(ACCOUNT_JSON, MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith(
                        "http://localhost:8080/api/v1/accounts/" + ACCOUNT + "/balance")))
                .andRespond(withSuccess(balanceBody, MediaType.APPLICATION_JSON));
    }

    private void expectStatement(String body) {
        server.expect(requestTo(startsWith(
                        "http://localhost:8080/api/v1/accounts/" + ACCOUNT + "/postings")))
                .andExpect(queryParam("limit", "20"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private Document getPage(String uri) throws Exception {
        MvcTestResult result = mvc.get().uri(uri)
                .with(ConsoleSessions.user("ops", "LEDGER_READ")).exchange();
        assertThat(result).hasStatusOk();
        return Jsoup.parse(result.getResponse().getContentAsString());
    }

    @Nested
    @DisplayName("Balance card")
    class BalanceCard {

        @Test
        @DisplayName("the headline is the NATURAL figure: LIABILITY raw −123.45 shows as 123.45")
        void natural_balance_is_the_headline() throws Exception {
            expectAccountAndBalance(balanceJson(""));
            expectStatement(statementJson(2, "CUR"));

            Document page = getPage("/accounts/" + ACCOUNT);

            assertThat(page.selectFirst(".balance-figure").text()).isEqualTo("123.45 EUR");
            assertThat(page.selectFirst(".balance-figure").hasClass("negative")).isFalse();
            assertThat(page.selectFirst(".balance-raw").text()).contains("-123.45 EUR", "2 postings");
            assertThat(page.selectFirst(".balance-label").text()).isEqualTo("Balance");
        }

        @Test
        @DisplayName("as-of: the at param is forwarded and the echoed asOf renders")
        void as_of_forwarded_and_labelled() throws Exception {
            server.expect(requestTo("http://localhost:8080/api/v1/accounts/" + ACCOUNT))
                    .andRespond(withSuccess(ACCOUNT_JSON, MediaType.APPLICATION_JSON));
            server.expect(requestTo(startsWith(
                            "http://localhost:8080/api/v1/accounts/" + ACCOUNT + "/balance")))
                    // URI-variable expansion strictly encodes ':' (the server decodes it
                    // back); queryParam compares the RAW query, hence the %3A form here.
                    .andExpect(queryParam("at", "2026-07-27T10%3A30%3A00Z"))
                    .andRespond(withSuccess(
                            balanceJson(", \"asOf\": \"2026-07-27T10:30:00Z\""),
                            MediaType.APPLICATION_JSON));
            expectStatement(statementJson(2, "CUR"));

            Document page = getPage("/accounts/" + ACCOUNT + "?at=2026-07-27T10:30:00Z");

            assertThat(page.selectFirst(".balance-label").text()).isEqualTo("Balance as of");
            assertThat(page.selectFirst(".balance-asof time").attr("datetime"))
                    .isEqualTo("2026-07-27T10:30:00Z");
            server.verify();
        }

        @Test
        @DisplayName("a negative NATURAL balance styles red — an allow-negative LIABILITY in net debit")
        void negative_natural_balance_styles_negative() throws Exception {
            server.expect(requestTo("http://localhost:8080/api/v1/accounts/" + ACCOUNT))
                    .andRespond(withSuccess(ACCOUNT_JSON.replace(
                            "\"allowNegative\": false", "\"allowNegative\": true"),
                            MediaType.APPLICATION_JSON));
            // Domain-consistent flip of the demo fixture: LIABILITY raw +12345 (net debit)
            // is natural −12345 — the direction the sole other cell never exercises.
            server.expect(requestTo(startsWith(
                            "http://localhost:8080/api/v1/accounts/" + ACCOUNT + "/balance")))
                    .andRespond(withSuccess("""
                            {"accountId": "%s", "type": "LIABILITY", \
                            "balance": {"amount": -12345, "currency": "EUR"}, \
                            "rawBalance": {"amount": 12345, "currency": "EUR"}, \
                            "postingCount": 2}""".formatted(ACCOUNT),
                            MediaType.APPLICATION_JSON));
            expectStatement(statementJson(0, null));

            Document page = getPage("/accounts/" + ACCOUNT);

            assertThat(page.selectFirst(".balance-figure").text()).isEqualTo("-123.45 EUR");
            assertThat(page.selectFirst(".balance-figure").hasClass("negative")).isTrue();
        }

        @Test
        @DisplayName("an unparseable at is a form error — live balance renders, no at reaches the API")
        void invalid_at_is_a_field_error() throws Exception {
            server.expect(requestTo("http://localhost:8080/api/v1/accounts/" + ACCOUNT))
                    .andRespond(withSuccess(ACCOUNT_JSON, MediaType.APPLICATION_JSON));
            server.expect(request -> assertThat(request.getURI().toString())
                            .contains("/balance").doesNotContain("at="))
                    .andRespond(withSuccess(balanceJson(""), MediaType.APPLICATION_JSON));
            expectStatement(statementJson(0, null));

            Document page = getPage("/accounts/" + ACCOUNT + "?at=yesterday-noon");

            assertThat(page.selectFirst(".field-error").text())
                    .contains("Not an ISO-8601 UTC instant");
            assertThat(page.selectFirst(".balance-label").text()).isEqualTo("Balance");
        }
    }

    @Nested
    @DisplayName("Statement + load more")
    class Statement {

        @Test
        @DisplayName("a full page grows the sentinel: cursor in hx-get, target is the sentinel row")
        void full_page_renders_sentinel() throws Exception {
            expectAccountAndBalance(balanceJson(""));
            expectStatement(statementJson(20, "CURSOR-20"));

            Document page = getPage("/accounts/" + ACCOUNT);

            assertThat(page.select("tbody tr")).hasSize(21);
            org.jsoup.nodes.Element button = page.selectFirst("button.load-more");
            assertThat(button.attr("hx-get"))
                    .isEqualTo("/accounts/" + ACCOUNT + "/statement?cursor=CURSOR-20");
            assertThat(button.attr("hx-target")).isEqualTo("#more-" + ACCOUNT);
            assertThat(button.attr("hx-swap")).isEqualTo("outerHTML");
            assertThat(page.selectFirst("tr#more-" + ACCOUNT)).isNotNull();
        }

        @Test
        @DisplayName("a short page means caught up — no sentinel, even though nextCursor is present")
        void short_page_hides_sentinel() throws Exception {
            expectAccountAndBalance(balanceJson(""));
            // nextCursor PRESENT (it always is on a non-empty page) — fullness decides.
            expectStatement(statementJson(3, "CURSOR-3"));

            Document page = getPage("/accounts/" + ACCOUNT);

            assertThat(page.select("tbody tr")).hasSize(3);
            assertThat(page.select("button.load-more")).isEmpty();
        }

        @Test
        @DisplayName("sides derive from the sign: positive raw renders DEBIT, negative CREDIT")
        void sides_derive_from_sign() throws Exception {
            expectAccountAndBalance(balanceJson(""));
            expectStatement(statementJson(2, "CUR"));

            Document page = getPage("/accounts/" + ACCOUNT);

            assertThat(page.select(".side-chip").eachText()).containsExactly("DEBIT", "CREDIT");
        }

        @Test
        @DisplayName("the htmx fragment returns rows + a fresh sentinel, no page chrome")
        void hx_fragment_returns_rows_and_new_sentinel() throws Exception {
            server.expect(requestTo(startsWith(
                            "http://localhost:8080/api/v1/accounts/" + ACCOUNT + "/postings")))
                    .andExpect(queryParam("cursor", "CURSOR-20"))
                    .andExpect(queryParam("limit", "20"))
                    .andRespond(withSuccess(statementJson(20, "CURSOR-40"),
                            MediaType.APPLICATION_JSON));

            MvcTestResult result = mvc.get()
                    .uri("/accounts/" + ACCOUNT + "/statement?cursor=CURSOR-20")
                    .header("HX-Request", "true")
                    .with(ConsoleSessions.user("ops", "LEDGER_READ"))
                    .exchange();

            assertThat(result).hasStatusOk();
            String body = result.getResponse().getContentAsString();
            assertThat(body).doesNotContain("<html");
            // Bare <tr> fragments need a table context or jsoup's HTML parser drops them
            // (htmx itself parses fragments inside a <template>, which has no such rule).
            Document fragment = Jsoup.parse("<table>" + body + "</table>");
            assertThat(fragment.select("tr")).hasSize(21);
            assertThat(fragment.selectFirst("button.load-more").attr("hx-get"))
                    .endsWith("cursor=CURSOR-40");
            server.verify();
        }

        @Test
        @DisplayName("an empty load-more fragment removes the button and says nothing at all")
        void empty_fragment_is_empty() throws Exception {
            server.expect(requestTo(startsWith(
                            "http://localhost:8080/api/v1/accounts/" + ACCOUNT + "/postings")))
                    .andRespond(withSuccess(statementJson(0, "CURSOR-20"),
                            MediaType.APPLICATION_JSON));

            MvcTestResult result = mvc.get()
                    .uri("/accounts/" + ACCOUNT + "/statement?cursor=CURSOR-20")
                    .header("HX-Request", "true")
                    .with(ConsoleSessions.user("ops", "LEDGER_READ"))
                    .exchange();

            assertThat(result).hasStatusOk();
            Document fragment = Jsoup.parse(
                    "<table>" + result.getResponse().getContentAsString() + "</table>");
            assertThat(fragment.select("tr")).isEmpty();
            // "No postings yet" is the PAGE's empty state; appending it mid-table would lie.
            assertThat(result.getResponse().getContentAsString()).doesNotContain("No postings");
            server.verify();
        }

        @Test
        @DisplayName("a cursor with template metacharacters reaches the API encoded, not a console 500")
        void crafted_cursor_travels_as_a_value() throws Exception {
            server.expect(requestTo(startsWith(
                            "http://localhost:8080/api/v1/accounts/" + ACCOUNT + "/postings")))
                    // The brace arrives %7B-encoded on the wire (queryParam compares raw)
                    // instead of detonating URI-template expansion console-side.
                    .andExpect(queryParam("cursor", "%7Bforged%7D"))
                    .andRespond(withSuccess(statementJson(0, null),
                            MediaType.APPLICATION_JSON));

            assertThat(mvc.get()
                    // The braces must reach the controller literally — via a template VAR,
                    // or MockMvc itself would demand a value for a variable named 'forged'.
                    .uri("/accounts/" + ACCOUNT + "/statement?cursor={c}", "{forged}")
                    .header("HX-Request", "true")
                    .with(ConsoleSessions.user("ops", "LEDGER_READ")))
                    .hasStatusOk();
            server.verify();
        }

        @Test
        @DisplayName("a non-htmx hit of the fragment URL bounces to the account page")
        void non_hx_fragment_request_redirects() throws Exception {
            assertThat(mvc.get().uri("/accounts/" + ACCOUNT + "/statement?cursor=X")
                    .with(ConsoleSessions.user("ops", "LEDGER_READ")))
                    .hasStatus3xxRedirection()
                    .hasRedirectedUrl("/accounts/" + ACCOUNT);
        }
    }
}
