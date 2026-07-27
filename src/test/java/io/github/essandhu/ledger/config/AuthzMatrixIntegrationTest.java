package io.github.essandhu.ledger.config;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.github.essandhu.ledger.support.LedgerIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/**
 * I13: the full endpoint × principal matrix (PLAN §5), as ONE data table so every milestone
 * appends rows. Principals: no token → 401; authenticated without the required role → 403 —
 * including a valid token with zero LEDGER roles. Since M2, LEDGER_WRITE guards the money
 * movers (journal entries, transfers, reversals) and nothing else. There is deliberately no
 * role hierarchy: ADMIN does NOT imply READ (composite roles in Keycloak are where convenience
 * bundles belong, PLAN §7) — so ADMIN-on-GET is a 403 cell, not a convenience 200, and
 * ADMIN-on-POST-entry likewise.
 *
 * <p>Tokens carry raw {@code realm_access} claims and run through the production
 * {@link LedgerRealmRoleConverter} — the mapping is on the tested path (the remaining leg,
 * real-issuer wiring, is the CI smoke's job). 401/403 responses are asserted to be RFC 9457
 * problem documents, not empty bodies.
 */
@LedgerIntegrationTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("I13: authorization matrix")
class AuthzMatrixIntegrationTest {

    private static final String NONE = "NONE";
    private static final String NO_ROLES = "NO_ROLES";

    @Autowired
    private MockMvcTester mvc;

    private String existingId;
    private String entryId;
    private String runId;

    @BeforeAll
    void createFixtures() throws Exception {
        MvcTestResult result = mvc.post().uri("/api/v1/accounts")
                .with(principal("LEDGER_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name": "authz-fixture-%s", "currency": "EUR", "type": "ASSET",
                         "allowNegative": false}
                        """.formatted(UUID.randomUUID()))
                .exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        existingId = JsonPath.read(result.getResponse().getContentAsString(), "$.id");

        // M2 entry sentinel: two accounts as ADMIN, one transfer as WRITE — each fixture built
        // with exactly the role the matrix says may build it. allowNegative on the target: a
        // transfer credits it (−amount), and this fixture must never trip the overdraft rule.
        String source = createFixtureAccount("authz-entry-src");
        String target = createFixtureAccount("authz-entry-tgt");
        MvcTestResult transfer = mvc.post().uri("/api/v1/transfers")
                .with(principal("LEDGER_WRITE"))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"sourceAccountId": "%s", "targetAccountId": "%s",
                         "amount": {"amount": 100, "currency": "EUR"},
                         "description": "authz-entry-fixture"}
                        """.formatted(source, target))
                .exchange();
        assertThat(transfer).hasStatus(HttpStatus.CREATED);
        entryId = JsonPath.read(transfer.getResponse().getContentAsString(), "$.id");

        // M6 run sentinel for the read cells, built with exactly the role that may build it.
        MvcTestResult run = mvc.post().uri("/api/v1/reconciliation-runs")
                .with(principal("LEDGER_ADMIN"))
                .exchange();
        assertThat(run).hasStatus(HttpStatus.CREATED);
        runId = JsonPath.read(run.getResponse().getContentAsString(), "$.id");
    }

    private String createFixtureAccount(String label) throws Exception {
        MvcTestResult result = mvc.post().uri("/api/v1/accounts")
                .with(principal("LEDGER_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name": "%s-%s", "currency": "EUR", "type": "ASSET",
                         "allowNegative": true}
                        """.formatted(label, UUID.randomUUID()))
                .exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    private static RequestPostProcessor principal(String role) {
        List<String> roles = switch (role) {
            case NO_ROLES -> List.of("offline_access"); // authenticated, no LEDGER_* roles
            default -> List.of(role);
        };
        return jwt().jwt(j -> j.claim("realm_access", Map.of("roles", roles)))
                .authorities(new LedgerRealmRoleConverter());
    }

    record Cell(String method, String uri, String principal, int expected) {
    }

    static Stream<Cell> matrix() {
        return Stream.of(
                // POST /accounts — LEDGER_ADMIN only (201 exercised in fixture + API tests; the
                // right-role cell here uses an invalid body: authz decides before validation,
                // so 400 proves access was granted without creating noise rows).
                new Cell("POST", "/api/v1/accounts", NONE, 401),
                new Cell("POST", "/api/v1/accounts", NO_ROLES, 403),
                new Cell("POST", "/api/v1/accounts", "LEDGER_READ", 403),
                new Cell("POST", "/api/v1/accounts", "LEDGER_WRITE", 403),
                new Cell("POST", "/api/v1/accounts", "LEDGER_ADMIN", 400),

                // GET /accounts — LEDGER_READ only (no hierarchy: ADMIN gets 403)
                new Cell("GET", "/api/v1/accounts", NONE, 401),
                new Cell("GET", "/api/v1/accounts", NO_ROLES, 403),
                new Cell("GET", "/api/v1/accounts", "LEDGER_READ", 200),
                new Cell("GET", "/api/v1/accounts", "LEDGER_WRITE", 403),
                new Cell("GET", "/api/v1/accounts", "LEDGER_ADMIN", 403),

                // GET /accounts/{existing}
                new Cell("GET", "EXISTING", NONE, 401),
                new Cell("GET", "EXISTING", NO_ROLES, 403),
                new Cell("GET", "EXISTING", "LEDGER_READ", 200),
                new Cell("GET", "EXISTING", "LEDGER_WRITE", 403),
                new Cell("GET", "EXISTING", "LEDGER_ADMIN", 403),

                // HEAD is served by @GetMapping handlers — the matchers must cover it
                // explicitly or it would fall through past the role rules.
                new Cell("HEAD", "/api/v1/accounts", NONE, 401),
                new Cell("HEAD", "/api/v1/accounts", NO_ROLES, 403),
                new Cell("HEAD", "/api/v1/accounts", "LEDGER_READ", 200),
                new Cell("HEAD", "EXISTING", NO_ROLES, 403),
                new Cell("HEAD", "EXISTING", "LEDGER_READ", 200),

                // namespace backstop: unlisted methods inside /api/v1 are denied for everyone
                new Cell("PUT", "/api/v1/accounts", "LEDGER_ADMIN", 403),
                new Cell("DELETE", "EXISTING", "LEDGER_ADMIN", 403),

                // PATCH /accounts/{existing} — LEDGER_ADMIN only ({} is a legal no-op PATCH)
                new Cell("PATCH", "EXISTING", NONE, 401),
                new Cell("PATCH", "EXISTING", NO_ROLES, 403),
                new Cell("PATCH", "EXISTING", "LEDGER_READ", 403),
                new Cell("PATCH", "EXISTING", "LEDGER_WRITE", 403),
                new Cell("PATCH", "EXISTING", "LEDGER_ADMIN", 200),

                // ── M2 money movers (PLAN §5): LEDGER_WRITE only — no hierarchy, ADMIN 403.
                // Right-role POST cells use an invalid body {} → 400 (access proven without
                // creating rows, same trick as POST /accounts above). Since M4 these cells
                // are doubly 400: the Idempotency-Key header is required and absent here —
                // either shape defect proves the same thing, that authz opened the door.
                new Cell("POST", "/api/v1/journal-entries", NONE, 401),
                new Cell("POST", "/api/v1/journal-entries", NO_ROLES, 403),
                new Cell("POST", "/api/v1/journal-entries", "LEDGER_READ", 403),
                new Cell("POST", "/api/v1/journal-entries", "LEDGER_ADMIN", 403),
                new Cell("POST", "/api/v1/journal-entries", "LEDGER_WRITE", 400),

                new Cell("POST", "/api/v1/transfers", NONE, 401),
                new Cell("POST", "/api/v1/transfers", NO_ROLES, 403),
                new Cell("POST", "/api/v1/transfers", "LEDGER_READ", 403),
                new Cell("POST", "/api/v1/transfers", "LEDGER_ADMIN", 403),
                new Cell("POST", "/api/v1/transfers", "LEDGER_WRITE", 400),

                // Reversal: {} is a LEGAL body here (the description is optional), so the {}
                // trick would create a row — the right-role no-rows proof rides on a malformed
                // path id instead: authorization decides before path-variable conversion, so
                // 400 (not 403) proves access was granted, and nothing can be reversed.
                new Cell("POST", "ENTRY_REVERSAL", NONE, 401),
                new Cell("POST", "ENTRY_REVERSAL", NO_ROLES, 403),
                new Cell("POST", "ENTRY_REVERSAL", "LEDGER_READ", 403),
                new Cell("POST", "ENTRY_REVERSAL", "LEDGER_ADMIN", 403),
                new Cell("POST", "/api/v1/journal-entries/not-a-uuid/reversal", "LEDGER_WRITE", 400),

                // GET /journal-entries/{entry} — LEDGER_READ only (no hierarchy: WRITE cannot
                // read back what it posts; composite roles in Keycloak are the convenience).
                new Cell("GET", "ENTRY", NONE, 401),
                new Cell("GET", "ENTRY", NO_ROLES, 403),
                new Cell("GET", "ENTRY", "LEDGER_READ", 200),
                new Cell("GET", "ENTRY", "LEDGER_WRITE", 403),
                new Cell("GET", "ENTRY", "LEDGER_ADMIN", 403),
                new Cell("HEAD", "ENTRY", NO_ROLES, 403),
                new Cell("HEAD", "ENTRY", "LEDGER_READ", 200),

                // PLAN §5 defines no journal-entries collection endpoint (statements are read
                // per account) — the namespace backstop holds it, even for LEDGER_READ.
                new Cell("GET", "/api/v1/journal-entries", "LEDGER_READ", 403),

                // ── M3 balance & statement reads (PLAN §5): LEDGER_READ only — the extra
                // path segment means the /accounts/* matchers do NOT cover these; each has
                // its own explicit GET and HEAD rules, and this is the proof they exist.
                new Cell("GET", "BALANCE", NONE, 401),
                new Cell("GET", "BALANCE", NO_ROLES, 403),
                new Cell("GET", "BALANCE", "LEDGER_READ", 200),
                new Cell("GET", "BALANCE", "LEDGER_WRITE", 403),
                new Cell("GET", "BALANCE", "LEDGER_ADMIN", 403),
                new Cell("HEAD", "BALANCE", NO_ROLES, 403),
                new Cell("HEAD", "BALANCE", "LEDGER_READ", 200),

                new Cell("GET", "POSTINGS", NONE, 401),
                new Cell("GET", "POSTINGS", NO_ROLES, 403),
                new Cell("GET", "POSTINGS", "LEDGER_READ", 200),
                new Cell("GET", "POSTINGS", "LEDGER_WRITE", 403),
                new Cell("GET", "POSTINGS", "LEDGER_ADMIN", 403),
                new Cell("HEAD", "POSTINGS", NO_ROLES, 403),
                new Cell("HEAD", "POSTINGS", "LEDGER_READ", 200),

                // ── M6 reconciliation (PLAN §5): the trigger is LEDGER_ADMIN only; run and
                // findings reads are LEDGER_READ like every other read — no hierarchy, so the
                // ADMIN that triggers cannot read back without READ. The right-role POST runs
                // a real sweep: there is no body to invalidate, and a sweep is additive-safe
                // (it appends only its own run row; gauges are per-last-run state).
                new Cell("POST", "/api/v1/reconciliation-runs", NONE, 401),
                new Cell("POST", "/api/v1/reconciliation-runs", NO_ROLES, 403),
                new Cell("POST", "/api/v1/reconciliation-runs", "LEDGER_READ", 403),
                new Cell("POST", "/api/v1/reconciliation-runs", "LEDGER_WRITE", 403),
                new Cell("POST", "/api/v1/reconciliation-runs", "LEDGER_ADMIN", 201),

                new Cell("GET", "RUN", NONE, 401),
                new Cell("GET", "RUN", NO_ROLES, 403),
                new Cell("GET", "RUN", "LEDGER_READ", 200),
                new Cell("GET", "RUN", "LEDGER_WRITE", 403),
                new Cell("GET", "RUN", "LEDGER_ADMIN", 403),
                new Cell("HEAD", "RUN", NO_ROLES, 403),
                new Cell("HEAD", "RUN", "LEDGER_READ", 200),

                // The findings path's extra segment needs its own matchers (the M3 lesson) —
                // these cells are the proof they exist.
                new Cell("GET", "RUN_FINDINGS", NONE, 401),
                new Cell("GET", "RUN_FINDINGS", NO_ROLES, 403),
                new Cell("GET", "RUN_FINDINGS", "LEDGER_READ", 200),
                new Cell("GET", "RUN_FINDINGS", "LEDGER_WRITE", 403),
                new Cell("GET", "RUN_FINDINGS", "LEDGER_ADMIN", 403),
                new Cell("HEAD", "RUN_FINDINGS", NO_ROLES, 403),
                new Cell("HEAD", "RUN_FINDINGS", "LEDGER_READ", 200),

                // springdoc surfaces: any authenticated principal, no role required
                new Cell("GET", "/v3/api-docs", NONE, 401),
                new Cell("GET", "/v3/api-docs", NO_ROLES, 200),
                new Cell("GET", "/swagger-ui.html", NONE, 401),
                new Cell("GET", "/swagger-ui.html", NO_ROLES, 302),

                // actuator: health is THE anonymous surface; everything else needs a token
                new Cell("GET", "/actuator/health", NONE, 200),
                new Cell("GET", "/actuator/metrics", NONE, 401),
                new Cell("GET", "/actuator/metrics", NO_ROLES, 200),
                new Cell("GET", "/actuator/prometheus", NONE, 401),
                new Cell("GET", "/actuator/prometheus", NO_ROLES, 200));
    }

    @ParameterizedTest(name = "{0} {1} as {2} → {3}")
    @MethodSource("matrix")
    @DisplayName("endpoint × principal")
    void matrix_cell(Cell cell) {
        String uri = switch (cell.uri()) {
            case "EXISTING" -> "/api/v1/accounts/" + existingId;
            case "ENTRY" -> "/api/v1/journal-entries/" + entryId;
            case "ENTRY_REVERSAL" -> "/api/v1/journal-entries/" + entryId + "/reversal";
            case "BALANCE" -> "/api/v1/accounts/" + existingId + "/balance";
            case "POSTINGS" -> "/api/v1/accounts/" + existingId + "/postings";
            case "RUN" -> "/api/v1/reconciliation-runs/" + runId;
            case "RUN_FINDINGS" -> "/api/v1/reconciliation-runs/" + runId + "/findings";
            default -> cell.uri();
        };
        var request = switch (cell.method()) {
            case "GET" -> mvc.get().uri(uri);
            case "HEAD" -> mvc.head().uri(uri);
            case "POST" -> mvc.post().uri(uri).contentType(MediaType.APPLICATION_JSON).content("{}");
            case "PATCH" -> mvc.patch().uri(uri).contentType(MediaType.APPLICATION_JSON).content("{}");
            case "PUT" -> mvc.put().uri(uri).contentType(MediaType.APPLICATION_JSON).content("{}");
            case "DELETE" -> mvc.delete().uri(uri);
            default -> throw new IllegalArgumentException(cell.method());
        };
        if (!NONE.equals(cell.principal())) {
            request = request.with(principal(cell.principal()));
        }
        MvcTestResult result = request.exchange();
        assertThat(result).hasStatus(cell.expected());
        if (cell.expected() == 401 || cell.expected() == 403) {
            // PLAN §5: errors are RFC 9457 — including the security layer's, which by Spring
            // default would be empty bodies. The WWW-Authenticate convention is kept alongside.
            assertThat(result).hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
            if (cell.expected() == 401) {
                assertThat(result.getResponse().getHeader("WWW-Authenticate")).startsWith("Bearer");
            }
        }
    }

    @Test
    @DisplayName("precedence: authorization is decided before existence — no enumeration leak")
    void wrong_role_beats_unknown_id() {
        String unknown = "/api/v1/accounts/" + UUID.randomUUID();
        assertThat(mvc.get().uri(unknown).with(principal("LEDGER_ADMIN"))) // wrong role for GET
                .hasStatus(HttpStatus.FORBIDDEN);
        assertThat(mvc.get().uri(unknown).with(principal("LEDGER_READ"))) // right role
                .hasStatus(HttpStatus.NOT_FOUND);
        assertThat(mvc.patch().uri(unknown).contentType(MediaType.APPLICATION_JSON).content("{}")
                .with(principal("LEDGER_READ"))) // wrong role for PATCH
                .hasStatus(HttpStatus.FORBIDDEN);
        assertThat(mvc.patch().uri(unknown).contentType(MediaType.APPLICATION_JSON).content("{}")
                .with(principal("LEDGER_ADMIN"))) // right role
                .hasStatus(HttpStatus.NOT_FOUND);
        // M3 reads follow the same precedence.
        assertThat(mvc.get().uri(unknown + "/balance").with(principal("LEDGER_WRITE")))
                .hasStatus(HttpStatus.FORBIDDEN);
        assertThat(mvc.get().uri(unknown + "/balance").with(principal("LEDGER_READ")))
                .hasStatus(HttpStatus.NOT_FOUND);
        assertThat(mvc.get().uri(unknown + "/postings").with(principal("LEDGER_WRITE")))
                .hasStatus(HttpStatus.FORBIDDEN);
        assertThat(mvc.get().uri(unknown + "/postings").with(principal("LEDGER_READ")))
                .hasStatus(HttpStatus.NOT_FOUND);
        // M6 reads follow the same precedence.
        String unknownRun = "/api/v1/reconciliation-runs/" + UUID.randomUUID();
        assertThat(mvc.get().uri(unknownRun).with(principal("LEDGER_ADMIN")))
                .hasStatus(HttpStatus.FORBIDDEN);
        assertThat(mvc.get().uri(unknownRun).with(principal("LEDGER_READ")))
                .hasStatus(HttpStatus.NOT_FOUND);
        assertThat(mvc.get().uri(unknownRun + "/findings").with(principal("LEDGER_ADMIN")))
                .hasStatus(HttpStatus.FORBIDDEN);
        assertThat(mvc.get().uri(unknownRun + "/findings").with(principal("LEDGER_READ")))
                .hasStatus(HttpStatus.NOT_FOUND);
    }
}
