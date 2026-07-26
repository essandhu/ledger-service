package io.github.essandhu.ledger.adapter.web;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
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
 * The balance HTTP surface (PLAN §5, M3) against real PostgreSQL: the O(1) snapshot read, the
 * postings-derived as-of read, and the pinned response contract — natural balance leads, raw
 * rides along, {@code asOf} present exactly on derived figures (absent, never null, on live
 * ones). Shared-context discipline as everywhere: marker names, per-scenario subjects, no
 * assertion over rows this test did not create.
 *
 * <p>{@code ?at=} note: Z-form instants are the wire contract (PLAN §5, pinned at M3). Offset
 * forms are deliberately uncontracted — a raw '+' is servlet-decoded to a space on a real
 * container while MockMvc encodes differently, so neither acceptance nor rejection can be
 * proven here, and what cannot be proven is not promised.
 */
@LedgerIntegrationTest
@DisplayName("Balance API (M3): snapshot current, postings-derived as-of, RFC 9457 misses")
class BalanceApiIntegrationTest {

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
        return "balance-api-" + UUID.randomUUID();
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

    private String createAccount(String name, String type, boolean allowNegative) {
        MvcTestResult result = mvc.post().uri("/api/v1/accounts").with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name": "%s", "currency": "EUR", "type": "%s", "allowNegative": %s}
                        """.formatted(name, type, allowNegative))
                .exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return JsonPath.read(body(result), "$.id");
    }

    /** Transfers {@code amount} source→target and returns the entry's posted_at (read back). */
    private Instant transfer(String source, String target, long amount) {
        MvcTestResult result = mvc.post().uri("/api/v1/transfers").with(writer(subject()))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"sourceAccountId": "%s", "targetAccountId": "%s",
                         "amount": {"amount": %d, "currency": "EUR"}}
                        """.formatted(source, target, amount))
                .exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return Instant.parse(JsonPath.read(body(result), "$.postedAt"));
    }

    private MvcTestResult getBalance(String accountId) {
        return mvc.get().uri("/api/v1/accounts/{id}/balance", accountId)
                .with(reader()).exchange();
    }

    private MvcTestResult getBalanceAt(String accountId, Instant at) {
        return mvc.get().uri("/api/v1/accounts/{id}/balance?at={at}", accountId, at.toString())
                .with(reader()).exchange();
    }

    @Nested
    @DisplayName("current: the live snapshot, O(1)")
    class Current {

        @Test
        @DisplayName("a debited ASSET account: natural = raw = +amount, postingCount counts legs, asOf ABSENT")
        void current_balance_reads_the_snapshot() {
            String cash = createAccount(marker("balance-cash"), "ASSET", true);
            String equity = createAccount(marker("balance-equity"), "EQUITY", true);
            transfer(cash, equity, 12345);

            MvcTestResult result = getBalance(cash);

            assertThat(result).hasStatusOk();
            String json = body(result);
            assertThat(JsonPath.<String>read(json, "$.accountId")).isEqualTo(cash);
            assertThat(JsonPath.<String>read(json, "$.type")).isEqualTo("ASSET");
            assertThat(JsonPath.<Integer>read(json, "$.balance.amount")).isEqualTo(12345);
            assertThat(JsonPath.<String>read(json, "$.balance.currency")).isEqualTo("EUR");
            assertThat(JsonPath.<Integer>read(json, "$.rawBalance.amount")).isEqualTo(12345);
            assertThat(JsonPath.<Integer>read(json, "$.postingCount")).isEqualTo(1);
            // Pinned at M3: asOf is ABSENT on the live snapshot — not null.
            assertThat(json).doesNotContain("\"asOf\"");
        }

        @Test
        @DisplayName("PLAN §4.2: a credited LIABILITY account shows natural +N over raw −N")
        void natural_balance_flips_the_raw_sign_for_credit_normal_accounts() {
            String cash = createAccount(marker("balance-src"), "ASSET", true);
            String wallet = createAccount(marker("balance-wallet"), "LIABILITY", false);
            transfer(cash, wallet, 700); // wallet is the CREDIT leg: raw −700

            String json = body(getBalance(wallet));

            assertThat(JsonPath.<Integer>read(json, "$.rawBalance.amount")).isEqualTo(-700);
            assertThat(JsonPath.<Integer>read(json, "$.balance.amount")).isEqualTo(700);
        }

        @Test
        @DisplayName("PLAN §4.5: FROZEN accounts still serve balance reads")
        void frozen_accounts_still_serve_reads() {
            String cash = createAccount(marker("balance-frozen"), "ASSET", true);
            String equity = createAccount(marker("balance-frozen-eq"), "EQUITY", true);
            transfer(cash, equity, 5);
            assertThat(mvc.patch().uri("/api/v1/accounts/{id}", cash).with(admin())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"status\": \"FROZEN\"}")).hasStatusOk();

            MvcTestResult result = getBalance(cash);

            assertThat(result).hasStatusOk();
            assertThat(JsonPath.<Integer>read(body(result), "$.balance.amount")).isEqualTo(5);
        }

        @Test
        @DisplayName("HEAD is served (matcher coverage) with an empty body")
        void head_is_served() {
            String cash = createAccount(marker("balance-head"), "ASSET", true);

            assertThat(mvc.head().uri("/api/v1/accounts/{id}/balance", cash).with(reader()))
                    .hasStatusOk();
        }
    }

    @Nested
    @DisplayName("as-of: derived from postings, never the snapshot")
    class AsOf {

        @Test
        @DisplayName("I10: the cut is INCLUSIVE — at the posting's own instant it is counted; 1µs earlier it is not")
        void as_of_cut_is_inclusive_and_exact() {
            String cash = createAccount(marker("asof-cash"), "ASSET", true);
            String equity = createAccount(marker("asof-equity"), "EQUITY", true);
            Instant postedAt = transfer(cash, equity, 999);

            String atBoundary = body(getBalanceAt(cash, postedAt));
            assertThat(JsonPath.<Integer>read(atBoundary, "$.balance.amount")).isEqualTo(999);
            assertThat(JsonPath.<Integer>read(atBoundary, "$.postingCount")).isEqualTo(1);
            assertThat(Instant.parse(JsonPath.read(atBoundary, "$.asOf"))).isEqualTo(postedAt);

            String justBefore = body(getBalanceAt(cash, postedAt.minus(1, ChronoUnit.MICROS)));
            assertThat(JsonPath.<Integer>read(justBefore, "$.balance.amount")).isZero();
            assertThat(JsonPath.<Integer>read(justBefore, "$.postingCount")).isZero();
        }

        @Test
        @DisplayName("I10: asOf(now) equals the current snapshot under quiesced writes")
        void as_of_now_equals_current() {
            String cash = createAccount(marker("asof-now-cash"), "ASSET", true);
            String equity = createAccount(marker("asof-now-eq"), "EQUITY", true);
            transfer(cash, equity, 11);
            Instant last = transfer(cash, equity, 31);

            String current = body(getBalance(cash));
            String asOf = body(getBalanceAt(cash, last));

            assertThat(JsonPath.<Integer>read(asOf, "$.rawBalance.amount"))
                    .isEqualTo(JsonPath.<Integer>read(current, "$.rawBalance.amount"))
                    .isEqualTo(42);
            assertThat(JsonPath.<Integer>read(asOf, "$.postingCount"))
                    .isEqualTo(JsonPath.<Integer>read(current, "$.postingCount"))
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("a sub-microsecond at is answered on the ledger's grid: the cut floors, asOf echoes the effective instant")
        void sub_microsecond_at_floors_to_the_grid() {
            String cash = createAccount(marker("asof-submicro"), "ASSET", true);
            String equity = createAccount(marker("asof-submicro-eq"), "EQUITY", true);
            Instant postedAt = transfer(cash, equity, 77);
            // Half a microsecond past the posting: every posted_at is on the grid, so
            // flooring evaluates posted_at <= at exactly — and the driver's sub-µs rounding
            // can never round the cut UP past a later posting.
            String offGrid = postedAt.plusNanos(500).toString();

            MvcTestResult result = mvc.get()
                    .uri("/api/v1/accounts/{id}/balance?at={at}", cash, offGrid)
                    .with(reader()).exchange();

            assertThat(result).hasStatusOk();
            String json = body(result);
            assertThat(JsonPath.<Integer>read(json, "$.balance.amount")).isEqualTo(77);
            assertThat(Instant.parse(JsonPath.read(json, "$.asOf"))).isEqualTo(postedAt);
        }

        @Test
        @DisplayName("an at outside the ledger's instant range → 400, never a 500 from the bind")
        void out_of_range_at_is_400() {
            String cash = createAccount(marker("asof-range"), "ASSET", true);

            MvcTestResult result = mvc.get()
                    .uri("/api/v1/accounts/{id}/balance?at={at}", cash,
                            "-1000000-01-01T00:00:00Z") // parseable Instant, unbindable timestamptz
                    .with(reader()).exchange();

            assertThat(result).hasStatus(HttpStatus.BAD_REQUEST)
                    .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        }

        @Test
        @DisplayName("a future at is answerable: the sum is well-defined (and merely provisional)")
        void future_as_of_is_served() {
            String cash = createAccount(marker("asof-future"), "ASSET", true);
            String equity = createAccount(marker("asof-future-eq"), "EQUITY", true);
            transfer(cash, equity, 8);

            String json = body(getBalanceAt(cash, Instant.parse("2200-01-01T00:00:00Z")));

            assertThat(JsonPath.<Integer>read(json, "$.balance.amount")).isEqualTo(8);
        }
    }

    @Nested
    @DisplayName("404 family: path-addressed misses (never the payload's 422)")
    class NotFound {

        @Test
        @DisplayName("unknown account id → bare 404 problem, for both variants")
        void unknown_account_is_404() {
            String unknown = UUID.randomUUID().toString();

            MvcTestResult current = getBalance(unknown);
            assertThat(current).hasStatus(HttpStatus.NOT_FOUND)
                    .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);

            MvcTestResult asOf = getBalanceAt(unknown, Instant.parse("2026-01-01T00:00:00Z"));
            assertThat(asOf).hasStatus(HttpStatus.NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("400 family: the request itself can never be valid")
    class Shape {

        @Test
        @DisplayName("malformed at → 400 problem")
        void malformed_at_is_400() {
            String cash = createAccount(marker("shape-cash"), "ASSET", true);

            MvcTestResult result = mvc.get()
                    .uri("/api/v1/accounts/{id}/balance?at={at}", cash, "not-a-timestamp")
                    .with(reader()).exchange();

            assertThat(result).hasStatus(HttpStatus.BAD_REQUEST)
                    .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        }

        @Test
        @DisplayName("malformed account uuid in the path → 400 (not 404)")
        void malformed_path_id_is_400() {
            assertThat(mvc.get().uri("/api/v1/accounts/not-a-uuid/balance").with(reader()))
                    .hasStatus(HttpStatus.BAD_REQUEST);
        }
    }
}
