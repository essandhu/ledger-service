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
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The accounts list: rows, server-side filters (the API's own query params, forwarded), and
 * offset paging with console-computed totalPages (the API sends none).
 */
@ConsoleWebTest
@DisplayName("Accounts page (M8b): rows, filters forwarded verbatim, offset paging")
class AccountsPageTest {

    private static final String OPERATING = "11111111-1111-1111-1111-111111111111";
    private static final String CUSTOMER = "22222222-2222-2222-2222-222222222222";

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

    private static String accountJson(String id, String name, String type, String status) {
        return """
                {"id": "%s", "name": "%s", "currency": "EUR", "type": "%s", "status": "%s", \
                "allowNegative": true, "createdAt": "2026-07-27T10:00:00Z", \
                "updatedAt": "2026-07-27T10:00:00Z"}""".formatted(id, name, type, status);
    }

    private Document getPage(String uri) throws Exception {
        MvcTestResult result = mvc.get().uri(uri)
                .with(ConsoleSessions.user("ops", "LEDGER_READ")).exchange();
        assertThat(result).hasStatusOk();
        return Jsoup.parse(result.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("rows render as links with type tag and status badge; unfiltered sends ONLY page+size")
    void rows_render() throws Exception {
        server.expect(requestTo(startsWith("http://localhost:8080/api/v1/accounts")))
                .andExpect(queryParam("page", "0"))
                .andExpect(queryParam("size", "20"))
                // No filter selected → no type/status params at all (the API 400s on
                // empty enum strings only when misconverted; absence is the contract).
                .andExpect(request -> assertThat(request.getURI().getQuery())
                        .doesNotContain("type=").doesNotContain("status="))
                .andRespond(withSuccess("""
                        {"content": [%s, %s], "page": 0, "size": 20, "totalElements": 2}"""
                        .formatted(
                                accountJson(OPERATING, "demo-operating", "LIABILITY", "ACTIVE"),
                                accountJson(CUSTOMER, "demo-frozen", "ASSET", "FROZEN")),
                        MediaType.APPLICATION_JSON));

        Document page = getPage("/accounts");

        assertThat(page.select("table.ruled tbody tr")).hasSize(2);
        assertThat(page.selectFirst("a[href$=/accounts/" + OPERATING + "]").text())
                .isEqualTo("demo-operating");
        assertThat(page.select(".status-badge.status-frozen").text()).isEqualTo("FROZEN");
        assertThat(page.select(".type-tag").eachText()).containsExactly("LIABILITY", "ASSET");
        server.verify();
    }

    @Test
    @DisplayName("the last page of an exact multiple: totalPages is a ceiling, and next is absent")
    void exact_multiple_last_page() throws Exception {
        // 40/20 discriminates ceilDiv from the total/size+1 mistake (which would say 3 pages).
        server.expect(requestTo(startsWith("http://localhost:8080/api/v1/accounts")))
                .andExpect(queryParam("page", "1"))
                .andRespond(withSuccess("""
                        {"content": [%s], "page": 1, "size": 20, "totalElements": 40}"""
                        .formatted(accountJson(OPERATING, "a", "ASSET", "ACTIVE")),
                        MediaType.APPLICATION_JSON));

        Document page = getPage("/accounts?type=ASSET&page=1");

        assertThat(page.select(".pager-status").text()).isEqualTo("Page 2 of 2 · 40 accounts");
        assertThat(page.selectFirst(".pager a[href*=page=0]")).isNotNull();
        assertThat(page.select(".pager a[href*=page=2]")).isEmpty();
    }

    @Test
    @DisplayName("filters forward the API's own enum params and echo in the selects")
    void filters_forward_and_echo() throws Exception {
        server.expect(requestTo(startsWith("http://localhost:8080/api/v1/accounts")))
                .andExpect(queryParam("type", "LIABILITY"))
                .andExpect(queryParam("status", "ACTIVE"))
                .andRespond(withSuccess(
                        "{\"content\": [], \"page\": 0, \"size\": 20, \"totalElements\": 0}",
                        MediaType.APPLICATION_JSON));

        Document page = getPage("/accounts?type=LIABILITY&status=ACTIVE");

        assertThat(page.select("select[name=type] option[selected]").val())
                .isEqualTo("LIABILITY");
        assertThat(page.select("select[name=status] option[selected]").val())
                .isEqualTo("ACTIVE");
        assertThat(page.select("td.empty")).hasSize(1);
        server.verify();
    }

    @Test
    @DisplayName("paging: totalPages is console-computed; prev/next preserve the filter")
    void paging_computed_and_links_preserve_filters() throws Exception {
        server.expect(requestTo(startsWith("http://localhost:8080/api/v1/accounts")))
                .andExpect(queryParam("page", "1"))
                .andRespond(withSuccess("""
                        {"content": [%s], "page": 1, "size": 20, "totalElements": 45}"""
                        .formatted(accountJson(OPERATING, "a", "ASSET", "ACTIVE")),
                        MediaType.APPLICATION_JSON));

        Document page = getPage("/accounts?type=ASSET&page=1");

        assertThat(page.select(".pager-status").text())
                .isEqualTo("Page 2 of 3 · 45 accounts");
        assertThat(page.selectFirst(".pager a[href*=page=0]").attr("href"))
                .contains("type=ASSET");
        assertThat(page.selectFirst(".pager a[href*=page=2]").attr("href"))
                .contains("type=ASSET");
    }
}
