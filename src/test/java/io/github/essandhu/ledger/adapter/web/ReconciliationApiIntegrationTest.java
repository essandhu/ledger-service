package io.github.essandhu.ledger.adapter.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import io.github.essandhu.ledger.config.LedgerRealmRoleConverter;
import io.github.essandhu.ledger.support.LedgerIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/**
 * The reconciliation read surface's error contract (M6): path-addressed misses are
 * bare 404 problems (the account/entry doctrine), malformed ids and out-of-bounds paging are
 * bare 400s. The happy paths live with the I15 proof
 * ({@code ReconciliationJobIntegrationTest}); this class pins the edges.
 */
@LedgerIntegrationTest
@DisplayName("M6 API edges: reconciliation-run 404s and paging validation")
class ReconciliationApiIntegrationTest {

    @Autowired
    private MockMvcTester mvc;

    @Test
    @DisplayName("an unknown run id is a bare 404 problem — for the run and for its findings")
    void unknown_run_is_not_found() {
        String unknown = "/api/v1/reconciliation-runs/" + UUID.randomUUID();
        assertThat(mvc.get().uri(unknown).with(reader()))
                .hasStatus(HttpStatus.NOT_FOUND)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(mvc.get().uri(unknown + "/findings").with(reader()))
                .hasStatus(HttpStatus.NOT_FOUND)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    @DisplayName("a malformed run id can never be valid — bare 400")
    void malformed_run_id_is_bad_request() {
        assertThat(mvc.get().uri("/api/v1/reconciliation-runs/not-a-uuid").with(reader()))
                .hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(mvc.get().uri("/api/v1/reconciliation-runs/not-a-uuid/findings")
                .with(reader()))
                .hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("a subject-less admin token is a defective CREDENTIAL: 401 before any sweep runs")
    void subjectless_token_cannot_trigger() {
        // The M2 posture: created_by (here triggered_by) comes from the JWT subject, and a
        // syntactically valid token without one is refused 401 + WWW-Authenticate — the
        // sweep is never started on an unattributable say-so.
        var result = mvc.post().uri("/api/v1/reconciliation-runs")
                .with(jwt().jwt(j -> j.subject("")
                        .claim("realm_access", Map.of("roles", List.of("LEDGER_ADMIN"))))
                        .authorities(new LedgerRealmRoleConverter()))
                .exchange();
        assertThat(result)
                .hasStatus(HttpStatus.UNAUTHORIZED)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result.getResponse().getHeader("WWW-Authenticate")).startsWith("Bearer");
    }

    @Test
    @DisplayName("findings paging bounds are enforced at the web boundary — bare 400s")
    void findings_paging_bounds_are_enforced() {
        String findings = "/api/v1/reconciliation-runs/" + UUID.randomUUID() + "/findings";
        // Bounds fail at binding, BEFORE the 404 existence check could run — like every
        // malformed request, the resource question is never reached.
        assertThat(mvc.get().uri(findings + "?size=0").with(reader()))
                .hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(mvc.get().uri(findings + "?size=101").with(reader()))
                .hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(mvc.get().uri(findings + "?page=-1").with(reader()))
                .hasStatus(HttpStatus.BAD_REQUEST);
    }

    private static RequestPostProcessor reader() {
        return jwt().jwt(j -> j.claim("realm_access", Map.of("roles", List.of("LEDGER_READ"))))
                .authorities(new LedgerRealmRoleConverter());
    }
}
