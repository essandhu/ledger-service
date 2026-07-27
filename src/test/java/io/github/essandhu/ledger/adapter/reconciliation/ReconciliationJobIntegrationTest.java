package io.github.essandhu.ledger.adapter.reconciliation;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.jayway.jsonpath.JsonPath;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import io.github.essandhu.ledger.config.LedgerRealmRoleConverter;
import io.github.essandhu.ledger.support.LedgerIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/**
 * I15 (ADR-0002): a snapshot corrupted via out-of-band superuser SQL — the only way drift can
 * exist — is flagged within one job run: a finding row with the exact delta, the DRIFT verdict
 * on the run, and nonzero {@code ledger.reconciliation.drift.*} gauges; a clean run asserts
 * zero findings and zeroed gauges. Driven through the HTTP trigger (the M5 posture: the proofs
 * cover the stack production clients hit — endpoint, use case, JobOperator, chunked step,
 * listener, metrics).
 *
 * <p>Shared-context discipline: every corruption targets a snapshot THIS test created and is
 * restored in {@code finally} on the same out-of-band connection (the JournalSchemaIntegrationTest
 * restore idiom), and each drift assertion runs its own closing CLEAN sweep — so the schema
 * and the last-run gauges leave every method exactly as consistent as they were found. Verdict
 * and gauge assertions are exact, not monotone: a sweep is inherently global, which is
 * sanctioned here because I4 holds for every application-written row BY CONSTRUCTION — only
 * this class's own (restored) corruption can ever make a sweep see drift.
 */
@LedgerIntegrationTest
@DisplayName("I15: reconciliation detects out-of-band snapshot drift")
class ReconciliationJobIntegrationTest {

    private static final String RUNS = "ledger.reconciliation.runs";
    private static final String DURATION = "ledger.reconciliation.duration";
    private static final String DRIFT_ACCOUNTS = "ledger.reconciliation.drift.accounts";
    private static final String DRIFT_ABSOLUTE = "ledger.reconciliation.drift.absolute";

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private PostgreSQLContainer postgres;

    @Test
    @DisplayName("shipped disabled: no reconciliation scheduler bean exists in the default context")
    void reconciliation_scheduler_is_absent_by_default() {
        assertThat(context.getBeanNamesForType(ReconciliationScheduler.class))
                .as("ledger.reconciliation.schedule.enabled defaults to false")
                .isEmpty();
    }

    @Test
    @DisplayName("a clean ledger reconciles CLEAN: zero findings for the run, zeroed gauges, a counted completed run")
    void clean_ledger_reconciles_clean() throws Exception {
        String source = createAccount("recon-clean-src");
        String target = createAccount("recon-clean-tgt");
        transfer(source, target, 100);

        double cleanBefore = counterValue(RUNS, "outcome", "clean");
        long durationBefore = durationCount();
        String subject = "recon-admin-" + UUID.randomUUID();

        MvcTestResult result = trigger(subject);
        assertThat(result).hasStatus(HttpStatus.CREATED);
        String body = result.getResponse().getContentAsString();
        String runId = JsonPath.read(body, "$.id");
        assertThat(result.getResponse().getHeader("Location"))
                .isEqualTo("/api/v1/reconciliation-runs/" + runId);
        assertThat(JsonPath.<String>read(body, "$.status")).isEqualTo("CLEAN");
        assertThat(JsonPath.<String>read(body, "$.triggeredBy")).isEqualTo(subject);
        assertThat(JsonPath.<Integer>read(body, "$.driftCount")).isZero();
        assertThat(JsonPath.<Integer>read(body, "$.currencyMismatchCount")).isZero();
        assertThat(JsonPath.<Integer>read(body, "$.postedAtMismatchCount")).isZero();
        assertThat(JsonPath.<Integer>read(body, "$.unbalancedCurrencyCount")).isZero();
        // The sweep is global: at least this test's two accounts were compared.
        assertThat(JsonPath.<Integer>read(body, "$.accountsChecked")).isGreaterThanOrEqualTo(2);

        // Read-back as LEDGER_READ: same resource, and its findings page is empty FOR THIS RUN
        // (never a global row count — findings accumulate across the JVM).
        MvcTestResult read = mvc.get().uri("/api/v1/reconciliation-runs/" + runId)
                .with(reader()).exchange();
        assertThat(read).hasStatus(HttpStatus.OK);
        assertThat(JsonPath.<String>read(read.getResponse().getContentAsString(), "$.status"))
                .isEqualTo("CLEAN");
        MvcTestResult findings = mvc.get()
                .uri("/api/v1/reconciliation-runs/" + runId + "/findings").with(reader())
                .exchange();
        assertThat(findings).hasStatus(HttpStatus.OK);
        assertThat(JsonPath.<Integer>read(findings.getResponse().getContentAsString(),
                "$.totalElements")).isZero();

        assertThat(counterValue(RUNS, "outcome", "clean")).isEqualTo(cleanBefore + 1);
        assertThat(durationCount()).isEqualTo(durationBefore + 1);
        assertThat(gaugeValue(DRIFT_ACCOUNTS)).isZero();
        assertThat(gaugeValue(DRIFT_ABSOLUTE)).isZero();
    }

    @Test
    @DisplayName("I15: a snapshot corrupted out-of-band is flagged — DRIFT verdict, the exact finding, gauges fire")
    void corrupted_snapshot_balance_is_flagged_with_exact_delta() throws Exception {
        String source = createAccount("recon-drift-src");
        String target = createAccount("recon-drift-tgt");
        transfer(source, target, 100);
        double driftBefore = counterValue(RUNS, "outcome", "drift");

        try {
            corruptBalance(source, 7); // true raw balance +100 (source leg is the debit) → 107
            MvcTestResult result = trigger("recon-admin-" + UUID.randomUUID());
            assertThat(result).hasStatus(HttpStatus.CREATED);
            String body = result.getResponse().getContentAsString();
            String runId = JsonPath.read(body, "$.id");
            assertThat(JsonPath.<String>read(body, "$.status")).isEqualTo("DRIFT");
            // Exact, not >=: every corrupting test restores before any other sweep can see it
            // (this class's own discipline), so this run's drift is exactly this corruption.
            assertThat(JsonPath.<Integer>read(body, "$.driftCount")).isEqualTo(1);

            MvcTestResult findings = mvc.get()
                    .uri("/api/v1/reconciliation-runs/" + runId + "/findings").with(reader())
                    .exchange();
            String findingsBody = findings.getResponse().getContentAsString();
            assertThat(JsonPath.<Integer>read(findingsBody, "$.totalElements")).isEqualTo(1);
            Map<String, Object> finding =
                    JsonPath.<List<Map<String, Object>>>read(findingsBody, "$.content").get(0);
            assertThat(finding.get("accountId")).isEqualTo(source);
            assertThat(((Number) finding.get("snapshotBalance")).longValue()).isEqualTo(107);
            assertThat(((Number) finding.get("computedBalance")).longValue()).isEqualTo(100);
            assertThat(((Number) finding.get("delta")).longValue()).isEqualTo(7);
            assertThat(((Number) finding.get("snapshotCount")).longValue()).isEqualTo(1);
            assertThat(((Number) finding.get("computedCount")).longValue()).isEqualTo(1);

            assertThat(counterValue(RUNS, "outcome", "drift")).isEqualTo(driftBefore + 1);
            assertThat(gaugeValue(DRIFT_ACCOUNTS)).isEqualTo(1);
            assertThat(gaugeValue(DRIFT_ABSOLUTE)).isEqualTo(7);
        } finally {
            restoreBalance(source, 7);
        }

        // The closing clean sweep: proves restoration AND the gauges' overwrite semantics —
        // "at last completed run" state, back to zero, leaving shared state as found.
        MvcTestResult clean = trigger("recon-admin-" + UUID.randomUUID());
        assertThat(JsonPath.<String>read(clean.getResponse().getContentAsString(), "$.status"))
                .isEqualTo("CLEAN");
        assertThat(gaugeValue(DRIFT_ACCOUNTS)).isZero();
        assertThat(gaugeValue(DRIFT_ABSOLUTE)).isZero();
    }

    @Test
    @DisplayName("the watermark has teeth: count-only drift (balance intact) is flagged with delta 0")
    void corrupted_posting_count_alone_is_flagged() throws Exception {
        String source = createAccount("recon-count-src");
        String target = createAccount("recon-count-tgt");
        transfer(source, target, 50);

        try {
            corruptPostingCount(source, 1); // true count 1 → snapshot claims 2, balance intact
            MvcTestResult result = trigger("recon-admin-" + UUID.randomUUID());
            String body = result.getResponse().getContentAsString();
            String runId = JsonPath.read(body, "$.id");
            assertThat(JsonPath.<String>read(body, "$.status")).isEqualTo("DRIFT");

            MvcTestResult findings = mvc.get()
                    .uri("/api/v1/reconciliation-runs/" + runId + "/findings").with(reader())
                    .exchange();
            String findingsBody = findings.getResponse().getContentAsString();
            assertThat(JsonPath.<Integer>read(findingsBody, "$.totalElements")).isEqualTo(1);
            Map<String, Object> finding =
                    JsonPath.<List<Map<String, Object>>>read(findingsBody, "$.content").get(0);
            assertThat(finding.get("accountId")).isEqualTo(source);
            assertThat(((Number) finding.get("delta")).longValue())
                    .as("balances agree — only the watermark convicts").isZero();
            assertThat(((Number) finding.get("snapshotCount")).longValue()).isEqualTo(2);
            assertThat(((Number) finding.get("computedCount")).longValue()).isEqualTo(1);
            // Balance-delta gauge stays zero while the account gauge fires: the two gauges
            // answer different questions (how many accounts vs how much money).
            assertThat(gaugeValue(DRIFT_ACCOUNTS)).isEqualTo(1);
            assertThat(gaugeValue(DRIFT_ABSOLUTE)).isZero();
        } finally {
            corruptPostingCount(source, -1);
        }

        MvcTestResult clean = trigger("recon-admin-" + UUID.randomUUID());
        assertThat(JsonPath.<String>read(clean.getResponse().getContentAsString(), "$.status"))
                .isEqualTo("CLEAN");
    }

    @Test
    @DisplayName("findings page like a report: id-ordered offset pages with a stable total")
    void findings_paginate_in_id_order() throws Exception {
        String a = createAccount("recon-page-a");
        String b = createAccount("recon-page-b");
        String c = createAccount("recon-page-c");

        try {
            corruptBalance(a, 1);
            corruptBalance(b, 2);
            corruptBalance(c, 3);
            MvcTestResult result = trigger("recon-admin-" + UUID.randomUUID());
            String runId = JsonPath.read(result.getResponse().getContentAsString(), "$.id");
            assertThat(JsonPath.<Integer>read(result.getResponse().getContentAsString(),
                    "$.driftCount")).isEqualTo(3);
            assertThat(gaugeValue(DRIFT_ABSOLUTE)).isEqualTo(6);

            MvcTestResult first = mvc.get()
                    .uri("/api/v1/reconciliation-runs/" + runId + "/findings?page=0&size=2")
                    .with(reader()).exchange();
            String firstBody = first.getResponse().getContentAsString();
            assertThat(JsonPath.<Integer>read(firstBody, "$.totalElements")).isEqualTo(3);
            List<String> firstIds = JsonPath.read(firstBody, "$.content[*].id");
            assertThat(firstIds).hasSize(2);

            MvcTestResult second = mvc.get()
                    .uri("/api/v1/reconciliation-runs/" + runId + "/findings?page=1&size=2")
                    .with(reader()).exchange();
            String secondBody = second.getResponse().getContentAsString();
            List<String> secondIds = JsonPath.read(secondBody, "$.content[*].id");
            assertThat(secondIds).hasSize(1);
            // UUIDv7 finding ids: id order IS detection order, and pages never overlap.
            assertThat(firstIds.get(0)).isLessThan(firstIds.get(1));
            assertThat(firstIds.get(1)).isLessThan(secondIds.get(0));
        } finally {
            restoreBalance(a, 1);
            restoreBalance(b, 2);
            restoreBalance(c, 3);
        }

        MvcTestResult clean = trigger("recon-admin-" + UUID.randomUUID());
        assertThat(JsonPath.<String>read(clean.getResponse().getContentAsString(), "$.status"))
                .isEqualTo("CLEAN");
    }

    // ── HTTP helpers ─────────────────────────────────────────────────────────────────────

    private MvcTestResult trigger(String subject) {
        return mvc.post().uri("/api/v1/reconciliation-runs").with(admin(subject)).exchange();
    }

    private String createAccount(String label) throws Exception {
        MvcTestResult result = mvc.post().uri("/api/v1/accounts")
                .with(admin("recon-fixture-admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name": "%s-%s", "currency": "EUR", "type": "ASSET",
                         "allowNegative": true}
                        """.formatted(label, UUID.randomUUID()))
                .exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    private void transfer(String source, String target, long amount) {
        MvcTestResult result = mvc.post().uri("/api/v1/transfers")
                .with(writer("recon-fixture-writer"))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"sourceAccountId": "%s", "targetAccountId": "%s",
                         "amount": {"amount": %d, "currency": "EUR"},
                         "description": "recon-fixture"}
                        """.formatted(source, target, amount))
                .exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
    }

    private static RequestPostProcessor admin(String subject) {
        return role(subject, "LEDGER_ADMIN");
    }

    private static RequestPostProcessor reader() {
        return role("recon-reader", "LEDGER_READ");
    }

    private static RequestPostProcessor writer(String subject) {
        return role(subject, "LEDGER_WRITE");
    }

    private static RequestPostProcessor role(String subject, String role) {
        return jwt().jwt(j -> j.subject(subject)
                        .claim("realm_access", Map.of("roles", List.of(role))))
                .authorities(new LedgerRealmRoleConverter());
    }

    // ── out-of-band corruption (superuser, per ADR-0002's I15 wording) ───────────────────

    private void corruptBalance(String accountId, long delta) throws SQLException {
        executeAsSuperuser(
                "UPDATE account_balance SET balance = balance + " + delta
                        + " WHERE account_id = ?", accountId);
    }

    private void restoreBalance(String accountId, long delta) throws SQLException {
        corruptBalance(accountId, -delta);
    }

    private void corruptPostingCount(String accountId, long delta) throws SQLException {
        executeAsSuperuser(
                "UPDATE account_balance SET posting_count = posting_count + " + delta
                        + " WHERE account_id = ?", accountId);
    }

    private void executeAsSuperuser(String sql, String accountId) throws SQLException {
        try (Connection superuser = DriverManager.getConnection(postgres.getJdbcUrl(),
                postgres.getUsername(), postgres.getPassword());
             PreparedStatement update = superuser.prepareStatement(sql)) {
            update.setObject(1, UUID.fromString(accountId));
            assertThat(update.executeUpdate()).isEqualTo(1);
        }
    }

    // ── metrics helpers (shared registry: deltas for counters, exact for last-run gauges) ─

    private double counterValue(String name, String tagKey, String tagValue) {
        Counter counter = meterRegistry.find(name).tags(tagKey, tagValue).counter();
        return counter == null ? 0 : counter.count();
    }

    private long durationCount() {
        Timer timer = meterRegistry.find(DURATION).timer();
        return timer == null ? 0 : timer.count();
    }

    private double gaugeValue(String name) {
        Gauge gauge = meterRegistry.find(name).gauge();
        assertThat(gauge).as("drift gauges are registered eagerly at boot").isNotNull();
        return gauge.value();
    }
}
