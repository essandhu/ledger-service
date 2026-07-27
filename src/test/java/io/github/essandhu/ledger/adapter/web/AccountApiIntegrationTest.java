package io.github.essandhu.ledger.adapter.web;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

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
 * The account CRUD + lifecycle HTTP surface against real PostgreSQL: observable
 * behavior only — status codes, RFC 9457 bodies with pinned type URIs, rows, headers.
 * Shared-context discipline: every row this class creates carries a
 * unique marker name, and no assertion quantifies over rows it did not create.
 */
@LedgerIntegrationTest
@DisplayName("Account API (M1): CRUD, lifecycle, RFC 9457 rejections")
class AccountApiIntegrationTest {

    private static final String PROBLEMS = "https://essandhu.github.io/ledger/problems/";

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private DataSource dataSource;

    private static RequestPostProcessor role(String... roles) {
        // Tokens carry the raw Keycloak claim shape and run through the PRODUCTION converter,
        // so the claim-to-authority mapping is on the tested path, not stubbed around.
        return jwt().jwt(j -> j.claim("realm_access", of("roles", List.of(roles))))
                .authorities(new LedgerRealmRoleConverter());
    }

    private static RequestPostProcessor admin() {
        return role("LEDGER_ADMIN");
    }

    private static RequestPostProcessor reader() {
        return role("LEDGER_READ");
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

    private MvcTestResult post(String json) {
        return mvc.post().uri("/api/v1/accounts").with(admin())
                .contentType(MediaType.APPLICATION_JSON).content(json).exchange();
    }

    private MvcTestResult patch(String id, String json) {
        return mvc.patch().uri("/api/v1/accounts/{id}", id).with(admin())
                .contentType(MediaType.APPLICATION_JSON).content(json).exchange();
    }

    private String createAccount(String name, String currency, String type, boolean allowNegative) {
        MvcTestResult result = post("""
                {"name": "%s", "currency": "%s", "type": "%s", "allowNegative": %s}
                """.formatted(name, currency, type, allowNegative));
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return JsonPath.read(body(result), "$.id");
    }

    private long versionInDb(String id) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT version FROM account WHERE id = ?")) {
            select.setObject(1, UUID.fromString(id));
            try (ResultSet row = select.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getLong("version");
            }
        }
    }

    @Nested
    @DisplayName("create + read")
    class CreateAndRead {

        @Test
        @DisplayName("POST creates an ACTIVE account: 201, Location, Clock-driven timestamps")
        void post_creates_active_account() {
            String name = marker("checking");
            // Truncated to match the production Clock's microsecond tick (ClockConfig): createdAt
            // is micros-truncated, but a raw Instant.now() captured in the SAME microsecond keeps
            // its sub-microsecond digits and can exceed createdAt — a spurious window failure.
            // Flooring the lower bound to the tick makes the comparison sound; the upper bound
            // needs no truncation (truncated createdAt <= real time <= after).
            Instant before = Instant.now().truncatedTo(ChronoUnit.MICROS);
            MvcTestResult result = post("""
                    {"name": "%s", "currency": "JPY", "type": "LIABILITY", "allowNegative": true}
                    """.formatted(name));
            Instant after = Instant.now();

            assertThat(result).hasStatus(HttpStatus.CREATED);
            String body = body(result);
            String id = JsonPath.read(body, "$.id");
            assertThat(result.getResponse().getHeader("Location")).endsWith("/api/v1/accounts/" + id);
            assertThat(JsonPath.<String>read(body, "$.name")).isEqualTo(name);
            assertThat(JsonPath.<String>read(body, "$.currency")).isEqualTo("JPY");
            assertThat(JsonPath.<String>read(body, "$.type")).isEqualTo("LIABILITY");
            assertThat(JsonPath.<String>read(body, "$.status")).isEqualTo("ACTIVE");
            assertThat(JsonPath.<Boolean>read(body, "$.allowNegative")).isTrue();

            // Window-bounds prove request-time generation; exact values are the unit layer's
            // job (fixed Clock), and ArchUnit proves no ambient time source exists at all.
            Instant createdAt = Instant.parse(JsonPath.read(body, "$.createdAt"));
            Instant updatedAt = Instant.parse(JsonPath.read(body, "$.updatedAt"));
            assertThat(createdAt).isEqualTo(updatedAt);
            assertThat(createdAt).isBetween(before, after);

            // Round-trip pin: the write response must be byte-identical to a fresh read.
            // Guards the microsecond-ticked Clock — a raw system clock would serialize nanos
            // here but microseconds after the timestamptz round-trip.
            assertThat(mvc.get().uri("/api/v1/accounts/{id}", id).with(reader()))
                    .hasStatusOk()
                    .bodyJson().extractingPath("$.createdAt")
                    .isEqualTo(JsonPath.read(body, "$.createdAt"));
        }

        @Test
        @DisplayName("GET by id returns the same representation")
        void get_returns_account() {
            String name = marker("savings");
            String id = createAccount(name, "EUR", "ASSET", false);
            assertThat(mvc.get().uri("/api/v1/accounts/{id}", id).with(reader()))
                    .hasStatusOk()
                    .bodyJson().extractingPath("$.name").isEqualTo(name);
        }

        @Test
        @DisplayName("GET unknown id → 404; malformed uuid → 400")
        void get_unknown_and_malformed_ids() {
            assertThat(mvc.get().uri("/api/v1/accounts/{id}", UUID.randomUUID()).with(reader()))
                    .hasStatus(HttpStatus.NOT_FOUND)
                    .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
            assertThat(mvc.get().uri("/api/v1/accounts/not-a-uuid").with(reader()))
                    .hasStatus(HttpStatus.BAD_REQUEST);
        }
    }

    @Nested
    @DisplayName("create validation (400 family: request well-formedness)")
    class CreateValidation {

        @Test
        @DisplayName("blank name, bad currency (non-ISO, pseudo, lowercase), bad type → 400 problem")
        void malformed_requests_are_400() {
            for (String json : List.of(
                    "{\"name\": \"  \", \"currency\": \"EUR\", \"type\": \"ASSET\", \"allowNegative\": false}",
                    "{\"name\": \"n\", \"currency\": \"ZZZ\", \"type\": \"ASSET\", \"allowNegative\": false}",
                    "{\"name\": \"n\", \"currency\": \"XXX\", \"type\": \"ASSET\", \"allowNegative\": false}",
                    "{\"name\": \"n\", \"currency\": \"eur\", \"type\": \"ASSET\", \"allowNegative\": false}",
                    "{\"name\": \"n\", \"currency\": \"EUR\", \"type\": \"FOO\", \"allowNegative\": false}",
                    "{\"name\": \"n\", \"currency\": \"EUR\", \"type\": \"asset\", \"allowNegative\": false}",
                    "{\"name\": \"n\", \"currency\": \"EUR\", \"type\": \"ASSET\"}",
                    "not json")) {
                assertThat(post(json)).as(json)
                        .hasStatus(HttpStatus.BAD_REQUEST)
                        .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
            }
        }

        @Test
        @DisplayName("POST carrying status → 422 field-not-writable (loud, never silently ignored)")
        void post_with_status_is_rejected() {
            assertThat(post("""
                    {"name": "%s", "currency": "EUR", "type": "ASSET", "allowNegative": false,
                     "status": "FROZEN"}
                    """.formatted(marker("sneaky"))))
                    .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                    .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                    .bodyJson().extractingPath("$.type").isEqualTo(PROBLEMS + "field-not-writable");
        }
    }

    @Nested
    @DisplayName("PATCH: rename, lifecycle, atomicity (I12 over HTTP)")
    class Patch {

        @Test
        @DisplayName("rename bumps updated_at, keeps created_at — verified from a fresh read, not the write response")
        void rename_works() {
            String id = createAccount(marker("old"), "EUR", "EXPENSE", false);
            String newName = marker("new");
            MvcTestResult result = patch(id, "{\"name\": \"%s\"}".formatted(newName));
            assertThat(result).hasStatusOk();
            String body = body(result);
            assertThat(JsonPath.<String>read(body, "$.name")).isEqualTo(newName);
            Instant createdAt = Instant.parse(JsonPath.read(body, "$.createdAt"));
            Instant updatedAt = Instant.parse(JsonPath.read(body, "$.updatedAt"));
            assertThat(updatedAt).isAfter(createdAt);

            // The PATCH response is serialized from the in-memory domain object; only a fresh
            // read proves the entity mapping actually persisted the change (a dropped field in
            // AccountJpaEntity.apply() would leave the response correct and the row stale).
            MvcTestResult reread = mvc.get().uri("/api/v1/accounts/{id}", id).with(reader()).exchange();
            String rereadBody = body(reread);
            assertThat(JsonPath.<String>read(rereadBody, "$.name")).isEqualTo(newName);
            assertThat(JsonPath.<String>read(rereadBody, "$.updatedAt"))
                    .isEqualTo(JsonPath.<String>read(body, "$.updatedAt"));
        }

        @Test
        @DisplayName("retrying a combined rename+close PATCH is a 200 no-op — the API's only retry story")
        void combined_rename_and_close_is_retryable() {
            String id = createAccount(marker("retire"), "EUR", "ASSET", false);
            String patchBody = "{\"name\": \"%s\", \"status\": \"CLOSED\"}".formatted(marker("retired"));
            MvcTestResult first = patch(id, patchBody);
            assertThat(first).hasStatusOk();
            // The response was "lost"; the client retries the identical request against the
            // now-renamed-and-closed account. No Idempotency-Key exists on account management —
            // natural idempotence must carry it: same 200, same representation, no write.
            MvcTestResult retry = patch(id, patchBody);
            assertThat(retry).hasStatusOk();
            assertThat(body(retry)).isEqualTo(body(first));
        }

        @Test
        @DisplayName("freeze → unfreeze → close walk the legal edges")
        void legal_lifecycle_walk() {
            String id = createAccount(marker("wallet"), "BHD", "ASSET", false);
            assertThat(patch(id, "{\"status\": \"FROZEN\"}")).hasStatusOk()
                    .bodyJson().extractingPath("$.status").isEqualTo("FROZEN");
            assertThat(patch(id, "{\"status\": \"ACTIVE\"}")).hasStatusOk()
                    .bodyJson().extractingPath("$.status").isEqualTo("ACTIVE");
            assertThat(patch(id, "{\"status\": \"CLOSED\"}")).hasStatusOk()
                    .bodyJson().extractingPath("$.status").isEqualTo("CLOSED");
        }

        @Test
        @DisplayName("re-closing a CLOSED account is a 200 no-op: no version bump, no updated_at drift")
        void reclose_is_noop() throws SQLException {
            String id = createAccount(marker("done"), "EUR", "INCOME", false);
            assertThat(patch(id, "{\"status\": \"CLOSED\"}")).hasStatusOk();
            long versionAfterClose = versionInDb(id);
            MvcTestResult first = mvc.get().uri("/api/v1/accounts/{id}", id).with(reader()).exchange();
            String updatedAt = JsonPath.read(body(first), "$.updatedAt");

            MvcTestResult reclose = patch(id, "{\"status\": \"CLOSED\"}");
            assertThat(reclose).hasStatusOk()
                    .bodyJson().extractingPath("$.updatedAt").isEqualTo(updatedAt);
            assertThat(versionInDb(id)).isEqualTo(versionAfterClose);
        }

        @Test
        @DisplayName("CLOSED is terminal: reopening/refreezing → 422 invalid-status-transition")
        void closed_is_terminal() {
            String id = createAccount(marker("tomb"), "EUR", "EQUITY", false);
            assertThat(patch(id, "{\"status\": \"CLOSED\"}")).hasStatusOk();
            for (String target : List.of("ACTIVE", "FROZEN")) {
                assertThat(patch(id, "{\"status\": \"%s\"}".formatted(target))).as(target)
                        .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                        .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                        .bodyJson().extractingPath("$.type")
                        .isEqualTo(PROBLEMS + "invalid-status-transition");
            }
        }

        @Test
        @DisplayName("rename on CLOSED → 422 account-closed")
        void rename_on_closed_is_rejected() {
            String id = createAccount(marker("sealed"), "EUR", "ASSET", false);
            assertThat(patch(id, "{\"status\": \"CLOSED\"}")).hasStatusOk();
            assertThat(patch(id, "{\"name\": \"%s\"}".formatted(marker("try"))))
                    .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                    .bodyJson().extractingPath("$.type").isEqualTo(PROBLEMS + "account-closed");
        }

        @Test
        @DisplayName("combined rename + illegal transition is atomic: 422 and the rename did not stick")
        void combined_patch_is_atomic() {
            String name = marker("stay");
            String id = createAccount(name, "EUR", "ASSET", false);
            assertThat(patch(id, "{\"status\": \"CLOSED\"}")).hasStatusOk();
            assertThat(patch(id, "{\"name\": \"%s\", \"status\": \"ACTIVE\"}".formatted(marker("gone"))))
                    .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(mvc.get().uri("/api/v1/accounts/{id}", id).with(reader()))
                    .hasStatusOk().bodyJson().extractingPath("$.name").isEqualTo(name);
        }

        @Test
        @DisplayName("type/currency/allowNegative are immutable: PATCH carrying them → 422 field-not-writable")
        void immutable_fields_are_rejected() {
            String id = createAccount(marker("fixed"), "EUR", "ASSET", false);
            for (String json : List.of(
                    "{\"type\": \"EXPENSE\"}",
                    "{\"currency\": \"USD\"}",
                    "{\"allowNegative\": true}")) {
                assertThat(patch(id, json)).as(json)
                        .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                        .bodyJson().extractingPath("$.type").isEqualTo(PROBLEMS + "field-not-writable");
            }
        }

        @Test
        @DisplayName("empty PATCH {} is a 200 no-op")
        void empty_patch_is_noop() {
            String id = createAccount(marker("asis"), "EUR", "ASSET", false);
            assertThat(patch(id, "{}")).hasStatusOk()
                    .bodyJson().extractingPath("$.status").isEqualTo("ACTIVE");
        }

        @Test
        @DisplayName("PATCH unknown id → 404")
        void patch_unknown_id() {
            assertThat(patch(UUID.randomUUID().toString(), "{\"name\": \"x\"}"))
                    .hasStatus(HttpStatus.NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("list + pagination (offset; additive-safe under the shared context)")
    class Listing {

        @Test
        @DisplayName("filtered listing pages through own rows exactly once, in creation (id) order")
        void pagination_is_stable_over_own_rows() {
            String prefix = "page-probe-" + UUID.randomUUID() + "-";
            List<String> created = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                created.add(createAccount(prefix + i, "USD", "EXPENSE", false));
            }

            // Walk pages of a filtered listing; other tests' rows may interleave — assertions
            // quantify ONLY over the marked rows (shared-schema discipline).
            List<String> seenOwn = collectOwnRows("type=EXPENSE&status=ACTIVE", 3, prefix);
            assertThat(seenOwn).as("each own row exactly once, in creation order")
                    .containsExactlyElementsOf(created);
        }

        @Test
        @DisplayName("status filter excludes; type filter excludes")
        void filters_exclude() {
            String name = marker("frozen-list");
            String id = createAccount(name, "USD", "INCOME", false);
            assertThat(patch(id, "{\"status\": \"FROZEN\"}")).hasStatusOk();

            // Full page walks, not a single size=100 page: a first-page-only check would go
            // vacuous (or falsely fail) once the shared schema accumulates >100 matching rows.
            assertThat(collectOwnNames("type=INCOME&status=FROZEN", 50, name)).contains(name);
            assertThat(collectOwnNames("type=INCOME&status=ACTIVE", 50, name)).doesNotContain(name);
        }

        /** Walks every page of a filtered listing collecting ids of rows whose name starts with
         * {@code prefix}. Bounded by totalElements so a page-parameter regression fails loudly
         * instead of looping until the CI job timeout. */
        private List<String> collectOwnRows(String filterQuery, int size, String prefix) {
            List<String> ownIds = new ArrayList<>();
            long totalElements = Long.MAX_VALUE;
            for (int page = 0; (long) page * size < totalElements; page++) {
                MvcTestResult result = mvc.get()
                        .uri("/api/v1/accounts?%s&page=%d&size=%d".formatted(filterQuery, page, size))
                        .with(reader()).exchange();
                assertThat(result).hasStatusOk();
                String body = body(result);
                totalElements = ((Number) JsonPath.read(body, "$.totalElements")).longValue();
                assertThat(JsonPath.<Integer>read(body, "$.size")).isEqualTo(size);
                List<String> names = JsonPath.read(body, "$.content[*].name");
                List<String> ids = JsonPath.read(body, "$.content[*].id");
                for (int i = 0; i < names.size(); i++) {
                    if (names.get(i).startsWith(prefix)) {
                        ownIds.add(ids.get(i));
                    }
                }
                if (names.isEmpty()) {
                    break; // totalElements shrank under concurrent-ish deletion — never expected
                }
            }
            return ownIds;
        }

        private List<String> collectOwnNames(String filterQuery, int size, String exactName) {
            List<String> names = new ArrayList<>();
            long totalElements = Long.MAX_VALUE;
            for (int page = 0; (long) page * size < totalElements; page++) {
                MvcTestResult result = mvc.get()
                        .uri("/api/v1/accounts?%s&page=%d&size=%d".formatted(filterQuery, page, size))
                        .with(reader()).exchange();
                assertThat(result).hasStatusOk();
                String body = body(result);
                totalElements = ((Number) JsonPath.read(body, "$.totalElements")).longValue();
                List<String> pageNames = JsonPath.read(body, "$.content[*].name");
                pageNames.stream().filter(exactName::equals).forEach(names::add);
                if (pageNames.isEmpty()) {
                    break;
                }
            }
            return names;
        }

        @Test
        @DisplayName("pagination params are validated: size > 100 or negative page → 400")
        void pagination_params_validated() {
            assertThat(mvc.get().uri("/api/v1/accounts?size=101").with(reader()))
                    .hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(mvc.get().uri("/api/v1/accounts?page=-1").with(reader()))
                    .hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(mvc.get().uri("/api/v1/accounts?type=NOPE").with(reader()))
                    .hasStatus(HttpStatus.BAD_REQUEST);
        }
    }
}
