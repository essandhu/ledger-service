package io.github.essandhu.ledger.adapter.web;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.github.essandhu.ledger.config.LedgerRealmRoleConverter;
import io.github.essandhu.ledger.support.LedgerIntegrationTest;

import static java.util.Map.of;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/**
 * The statement HTTP surface (PLAN §5, M3) against real PostgreSQL: the keyset walk — exact,
 * ordered, duplicate-free — the (from, to] window composing with I10's algebra over the
 * balance endpoint, the always-resumable cursor under live appends (the M3 demo), and the
 * strict cursor rejections. Every line's strictly-increasing posted_at is asserted on the
 * walk, which end-to-end-proves the PLAN §4.6 clamp through the real stack.
 */
@LedgerIntegrationTest
@DisplayName("Statement API (M3): keyset paging, (from, to] window, live-append resume, strict cursors")
class StatementApiIntegrationTest {

    @Autowired
    private MockMvcTester mvc;

    private static RequestPostProcessor role(String... roles) {
        return jwt().jwt(j -> j.claim("realm_access", of("roles", List.of(roles))))
                .authorities(new LedgerRealmRoleConverter());
    }

    private static RequestPostProcessor admin() {
        return role("LEDGER_ADMIN");
    }

    private static RequestPostProcessor reader() {
        return role("LEDGER_READ");
    }

    private static RequestPostProcessor writer(String subject) {
        return jwt().jwt(j -> j.subject(subject)
                        .claim("realm_access", of("roles", List.of("LEDGER_WRITE"))))
                .authorities(new LedgerRealmRoleConverter());
    }

    private static String subject() {
        return "statement-api-" + UUID.randomUUID();
    }

    private String marker(String label) {
        return label + "-" + UUID.randomUUID();
    }

    private static String body(MvcTestResult result) {
        try {
            return result.getResponse().getContentAsString();
        } catch (java.io.UnsupportedEncodingException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private String createAccount(String name, String type) {
        MvcTestResult result = mvc.post().uri("/api/v1/accounts").with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name": "%s", "currency": "EUR", "type": "%s", "allowNegative": true}
                        """.formatted(name, type))
                .exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return JsonPath.read(body(result), "$.id");
    }

    /** Transfers source→target and returns the created entry id. */
    private String transfer(String source, String target, long amount) {
        MvcTestResult result = mvc.post().uri("/api/v1/transfers").with(writer(subject()))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"sourceAccountId": "%s", "targetAccountId": "%s",
                         "amount": {"amount": %d, "currency": "EUR"}}
                        """.formatted(source, target, amount))
                .exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return JsonPath.read(body(result), "$.id");
    }

    private MvcTestResult getPage(String accountId, String query) {
        return mvc.get().uri("/api/v1/accounts/" + accountId + "/postings" + query)
                .with(reader()).exchange();
    }

    private long rawBalanceAt(String accountId, Instant at) {
        MvcTestResult result = mvc.get()
                .uri("/api/v1/accounts/{id}/balance?at={at}", accountId, at.toString())
                .with(reader()).exchange();
        assertThat(result).hasStatusOk();
        return JsonPath.<Number>read(body(result), "$.rawBalance.amount").longValue();
    }

    @Nested
    @DisplayName("the keyset walk")
    class Walk {

        @Test
        @DisplayName("pages visit every posting exactly once, in (posted_at, id) order, with strictly increasing posted_at")
        void walk_is_exact_ordered_and_duplicate_free() {
            String cash = createAccount(marker("stmt-cash"), "ASSET");
            String equity = createAccount(marker("stmt-equity"), "EQUITY");
            long[] amounts = {1, 2, 4, 8, 16};
            List<String> entryIds = new ArrayList<>();
            for (long amount : amounts) {
                entryIds.add(transfer(cash, equity, amount));
            }

            List<String> seenIds = new ArrayList<>();
            List<Long> seenAmounts = new ArrayList<>();
            List<String> seenEntryIds = new ArrayList<>();
            Instant previous = null;
            String query = "?limit=2";
            int pages = 0;
            while (true) {
                assertThat(pages++).as("walk must terminate").isLessThan(10);
                String json = body(getPage(cash, query));
                List<String> ids = JsonPath.read(json, "$.content[*].id");
                if (ids.isEmpty()) {
                    break;
                }
                for (int i = 0; i < ids.size(); i++) {
                    seenIds.add(JsonPath.read(json, "$.content[" + i + "].id"));
                    seenAmounts.add(JsonPath.<Number>read(json,
                            "$.content[" + i + "].amount.amount").longValue());
                    seenEntryIds.add(JsonPath.read(json, "$.content[" + i + "].entryId"));
                    Instant postedAt = Instant.parse(
                            JsonPath.read(json, "$.content[" + i + "].postedAt"));
                    if (previous != null) {
                        // PLAN §4.6's clamp, observed end-to-end: strictly increasing
                        // per-account posted_at means the id tiebreak never even engages
                        // across entries.
                        assertThat(postedAt).isAfter(previous);
                    }
                    previous = postedAt;
                }
                String next = JsonPath.read(json, "$.nextCursor");
                assertThat(next).isNotNull();
                query = "?limit=2&cursor=" + next;
            }

            assertThat(seenAmounts).containsExactly(1L, 2L, 4L, 8L, 16L); // source = DEBIT, +amount
            assertThat(seenEntryIds).containsExactlyElementsOf(entryIds);
            assertThat(Set.copyOf(seenIds)).as("no duplicates").hasSize(5);
        }

        @Test
        @DisplayName("live appends: the empty page echoes the cursor, and re-polling it picks up new postings (the M3 demo)")
        void live_appends_resume_from_the_echoed_cursor() {
            String cash = createAccount(marker("stmt-tail"), "ASSET");
            String equity = createAccount(marker("stmt-tail-eq"), "EQUITY");
            transfer(cash, equity, 100);

            String firstPage = body(getPage(cash, "?limit=10"));
            String cursor = JsonPath.read(firstPage, "$.nextCursor");
            assertThat(cursor).isNotNull();

            String caughtUp = body(getPage(cash, "?cursor=" + cursor));
            assertThat(JsonPath.<List<Object>>read(caughtUp, "$.content")).isEmpty();
            // Pinned at M3: the empty page hands the SAME position back — stateless tailing.
            assertThat(JsonPath.<String>read(caughtUp, "$.nextCursor")).isEqualTo(cursor);

            transfer(cash, equity, 200); // a live append while the client was caught up

            String resumed = body(getPage(cash, "?cursor=" + cursor));
            List<Object> lines = JsonPath.read(resumed, "$.content");
            assertThat(lines).hasSize(1);
            assertThat(JsonPath.<Number>read(resumed, "$.content[0].amount.amount").longValue())
                    .isEqualTo(200L);
            assertThat(JsonPath.<String>read(resumed, "$.nextCursor")).isNotEqualTo(cursor);
        }

        @Test
        @DisplayName("an empty account's first page: no lines, null cursor (genesis IS \"no cursor\")")
        void empty_first_page_has_null_cursor() {
            String lonely = createAccount(marker("stmt-empty"), "ASSET");

            String json = body(getPage(lonely, ""));

            assertThat(JsonPath.<List<Object>>read(json, "$.content")).isEmpty();
            assertThat(JsonPath.<String>read(json, "$.nextCursor")).isNull();
        }

        @Test
        @DisplayName("the default limit is 20")
        void default_limit_is_twenty() {
            String cash = createAccount(marker("stmt-default"), "ASSET");
            String equity = createAccount(marker("stmt-default-eq"), "EQUITY");
            for (int i = 1; i <= 21; i++) {
                transfer(cash, equity, i);
            }

            String json = body(getPage(cash, ""));

            assertThat(JsonPath.<List<Object>>read(json, "$.content")).hasSize(20);
        }

        @Test
        @DisplayName("HEAD is served (matcher coverage)")
        void head_is_served() {
            String cash = createAccount(marker("stmt-head"), "ASSET");

            assertThat(mvc.head().uri("/api/v1/accounts/{id}/postings", cash).with(reader()))
                    .hasStatusOk();
        }
    }

    @Nested
    @DisplayName("the (from, to] window")
    class Window {

        @Test
        @DisplayName("I10's algebra, live over HTTP: Σ statement(from, to] = asOf(to) − asOf(from)")
        void window_composes_with_the_as_of_algebra() {
            String cash = createAccount(marker("stmt-window"), "ASSET");
            String equity = createAccount(marker("stmt-window-eq"), "EQUITY");
            transfer(cash, equity, 1);
            Instant from = postedAtOf(cash, 1); // the FIRST posting's instant — excluded
            transfer(cash, equity, 2);
            transfer(cash, equity, 4);
            Instant to = postedAtOf(cash, 3); // the third posting's instant — included

            String json = body(getPage(cash, "?from=" + from + "&to=" + to));

            List<Object> lines = JsonPath.read(json, "$.content");
            assertThat(lines).hasSize(2); // from excluded, to included
            long sum = 0;
            for (int i = 0; i < lines.size(); i++) {
                sum += JsonPath.<Number>read(json, "$.content[" + i + "].amount.amount").longValue();
            }
            assertThat(sum).isEqualTo(rawBalanceAt(cash, to) - rawBalanceAt(cash, from))
                    .isEqualTo(6);
        }

        @Test
        @DisplayName("an inverted window (from >= to) is a valid request with an empty answer")
        void inverted_window_is_empty_not_an_error() {
            String cash = createAccount(marker("stmt-inverted"), "ASSET");
            String equity = createAccount(marker("stmt-inverted-eq"), "EQUITY");
            transfer(cash, equity, 5);
            Instant at = postedAtOf(cash, 1);

            MvcTestResult result = getPage(cash, "?from=" + at.plusSeconds(60) + "&to=" + at);

            assertThat(result).hasStatusOk();
            assertThat(JsonPath.<List<Object>>read(body(result), "$.content")).isEmpty();
        }

        /** The n-th (1-based) statement line's postedAt, read off an unbounded first page. */
        private Instant postedAtOf(String accountId, int position) {
            String json = body(getPage(accountId, "?limit=100"));
            return Instant.parse(
                    JsonPath.read(json, "$.content[" + (position - 1) + "].postedAt"));
        }
    }

    @Nested
    @DisplayName("400 family: strict cursors and bounded limits")
    class Shape {

        @Test
        @DisplayName("a cursor is only valid for the account that issued it — a foreign cursor is a 400, not a truncated 200")
        void foreign_cursor_is_rejected() {
            String cash = createAccount(marker("stmt-bind"), "ASSET");
            String equity = createAccount(marker("stmt-bind-eq"), "EQUITY");
            transfer(cash, equity, 9);
            String cursor = JsonPath.read(body(getPage(cash, "?limit=1")), "$.nextCursor");
            assertThat(cursor).isNotNull();

            MvcTestResult result = getPage(equity, "?cursor=" + cursor);

            assertThat(result).hasStatus(HttpStatus.BAD_REQUEST)
                    .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
            assertThat(JsonPath.<String>read(body(result), "$.detail"))
                    .contains("different account");
        }

        @Test
        @DisplayName("garbage cursors are 400 problems")
        void garbage_cursor_is_rejected() {
            String cash = createAccount(marker("stmt-garbage"), "ASSET");

            MvcTestResult result = getPage(cash, "?cursor=%%%definitely-not-ours");

            assertThat(result).hasStatus(HttpStatus.BAD_REQUEST)
                    .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        }

        @Test
        @DisplayName("limit is bounded to [1, 100]")
        void limit_is_bounded() {
            String cash = createAccount(marker("stmt-limit"), "ASSET");

            assertThat(getPage(cash, "?limit=0")).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(getPage(cash, "?limit=101")).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(getPage(cash, "?limit=100")).hasStatusOk();
        }

        @Test
        @DisplayName("malformed or out-of-range from/to → 400")
        void malformed_window_is_400() {
            String cash = createAccount(marker("stmt-badwindow"), "ASSET");

            assertThat(getPage(cash, "?from=yesterday")).hasStatus(HttpStatus.BAD_REQUEST);
            // Parseable Instant, unbindable timestamptz — must be the 400 family, never a 500.
            assertThat(getPage(cash, "?to=-1000000-01-01T00:00:00Z"))
                    .hasStatus(HttpStatus.BAD_REQUEST);
        }
    }

    @Nested
    @DisplayName("404 family: path-addressed misses")
    class NotFound {

        @Test
        @DisplayName("unknown account id → bare 404 problem (never an empty 200)")
        void unknown_account_is_404() {
            MvcTestResult result = getPage(UUID.randomUUID().toString(), "");

            assertThat(result).hasStatus(HttpStatus.NOT_FOUND)
                    .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        }
    }
}
