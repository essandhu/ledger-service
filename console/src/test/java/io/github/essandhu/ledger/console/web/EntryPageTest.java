package io.github.essandhu.ledger.console.web;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The entry inspector — the M8b demo criterion as a test: ±12345 legs sum to a RENDERED zero,
 * one total row per currency (I1 is judged per currency, never across). The totals are
 * recomputed from the wire amounts; an unbalanced fixture renders its accusation honestly.
 */
@ConsoleWebTest
@DisplayName("Entry inspector (M8b): legs, per-currency zero totals, reversal linkage")
class EntryPageTest {

    private static final String ENTRY = "44444444-4444-4444-4444-444444444444";
    private static final String ORIGINAL = "55555555-5555-5555-5555-555555555555";
    private static final String SRC = "66666666-6666-6666-6666-666666666666";
    private static final String TGT = "77777777-7777-7777-7777-777777777777";

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

    private static String posting(String account, long amount, String currency) {
        return """
                {"id": "%s", "accountId": "%s", "amount": {"amount": %d, "currency": "%s"}}"""
                .formatted(new java.util.UUID(9, Math.abs(amount) + account.hashCode()),
                        account, amount, currency);
    }

    private static String entryJson(String type, String reversalOf, String postings) {
        return """
                {"id": "%s", "entryType": "%s", "description": "demo transfer", \
                "reversalOf": %s, "createdBy": "service-account-ledger-cli", \
                "postedAt": "2026-07-27T10:00:00Z", "postings": [%s]}"""
                .formatted(ENTRY, type,
                        reversalOf == null ? "null" : "\"" + reversalOf + "\"", postings);
    }

    private Document getEntry(String json) throws Exception {
        server.expect(requestTo("http://localhost:8080/api/v1/journal-entries/" + ENTRY))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
        MvcTestResult result = mvc.get().uri("/entries/" + ENTRY)
                .with(ConsoleSessions.user("ops", "LEDGER_READ")).exchange();
        assertThat(result).hasStatusOk();
        return Jsoup.parse(result.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("the demo transfer: ±12345 sums to a rendered 0.00 EUR, stamped balanced")
    void transfer_legs_sum_to_rendered_zero() throws Exception {
        Document page = getEntry(entryJson("TRANSFER", null,
                posting(SRC, 12345, "EUR") + ", " + posting(TGT, -12345, "EUR")));

        assertThat(page.select("tbody .side-chip").eachText())
                .containsExactly("DEBIT", "CREDIT");
        assertThat(page.select("tbody td.num").eachText())
                .containsExactly("123.45 EUR", "-123.45 EUR");

        org.jsoup.nodes.Element total = page.selectFirst("tr.total-row");
        assertThat(total.hasClass("balanced")).isTrue();
        assertThat(total.selectFirst(".total-label").text()).isEqualTo("Σ EUR");
        assertThat(total.selectFirst("td.num").text()).isEqualTo("0.00 EUR");
        assertThat(total.selectFirst(".stamp").text()).isEqualTo("balanced");
        // The IFF's other direction: a non-REVERSAL entry carries no reversal note.
        assertThat(page.select(".reversal-note")).isEmpty();
    }

    @Test
    @DisplayName("multi-currency entries get one zero total PER CURRENCY — never a cross-currency sum")
    void multi_currency_totals_are_per_currency() throws Exception {
        Document page = getEntry(entryJson("JOURNAL", null,
                posting(SRC, 1000, "EUR") + ", " + posting(TGT, -1000, "EUR") + ", "
                        + posting(SRC, 5, "JPY") + ", " + posting(TGT, -5, "JPY")));

        assertThat(page.select("tr.total-row")).hasSize(2);
        assertThat(page.select("tr.total-row .total-label").eachText())
                .containsExactly("Σ EUR", "Σ JPY");
        assertThat(page.select("tr.total-row td.num").eachText())
                .containsExactly("0.00 EUR", "0 JPY");
        assertThat(page.select("tr.total-row.balanced")).hasSize(2);
    }

    @Test
    @DisplayName("a REVERSAL names the entry it negates, linked")
    void reversal_links_the_original() throws Exception {
        Document page = getEntry(entryJson("REVERSAL", ORIGINAL,
                posting(SRC, -12345, "EUR") + ", " + posting(TGT, 12345, "EUR")));

        org.jsoup.nodes.Element note = page.selectFirst(".reversal-note");
        assertThat(note.text()).contains("exactly negates");
        assertThat(note.selectFirst("a").attr("href")).isEqualTo("/entries/" + ORIGINAL);
        assertThat(page.selectFirst(".type-tag").text()).isEqualTo("REVERSAL");
    }

    @Test
    @DisplayName("legs link to their accounts; the header carries postedAt and createdBy")
    void legs_link_and_header_renders() throws Exception {
        Document page = getEntry(entryJson("TRANSFER", null,
                posting(SRC, 12345, "EUR") + ", " + posting(TGT, -12345, "EUR")));

        assertThat(page.selectFirst("tbody a").attr("href")).isEqualTo("/accounts/" + SRC);
        assertThat(page.selectFirst(".account-times time").attr("datetime"))
                .isEqualTo("2026-07-27T10:00:00Z");
        assertThat(page.selectFirst(".account-times").text())
                .contains("service-account-ledger-cli");
    }

    @Test
    @DisplayName("an unbalanced fixture is accused, not smoothed over — the renderer computes, it doesn't trust")
    void unbalanced_renders_the_accusation() throws Exception {
        // The API cannot serve this (I1 is enforced at posting time); the DEFENSIVE cell
        // pins that the console recomputes rather than assuming.
        Document page = getEntry(entryJson("JOURNAL", null,
                posting(SRC, 12345, "EUR") + ", " + posting(TGT, -12338, "EUR")));

        org.jsoup.nodes.Element total = page.selectFirst("tr.total-row");
        assertThat(total.hasClass("unbalanced")).isTrue();
        assertThat(total.selectFirst(".stamp").text()).isEqualTo("DOES NOT BALANCE");
        assertThat(total.selectFirst("td.num").text()).isEqualTo("0.07 EUR");
    }
}
