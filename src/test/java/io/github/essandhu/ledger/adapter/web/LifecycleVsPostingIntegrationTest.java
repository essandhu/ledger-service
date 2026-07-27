package io.github.essandhu.ledger.adapter.web;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
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
 * I12, posting half, over HTTP: FROZEN and CLOSED accounts reject postings in BOTH directions
 * (a leg in either sign); closing requires natural balance zero, judged under the
 * SAME account_balance lock the posting path holds (ADR-0003) — so the close-vs-post race has
 * exactly two legal outcomes and no third: either the transfer commits first and the close
 * rejects with {@code account-balance-not-zero}, or the close commits first and the transfer
 * rejects with {@code account-closed}. A bounded race loop hunts for any other interleaving —
 * in particular a posting landing on a CLOSED account, which must never exist.
 */
@LedgerIntegrationTest
@DisplayName("I12 (posting half): lifecycle status gates postings; close and post serialize on one lock")
class LifecycleVsPostingIntegrationTest {

    private static final String PROBLEMS = "https://essandhu.github.io/ledger/problems/";

    /** Race-loop iterations: enough to catch an unserialized implementation with high
     * probability, small enough to keep the suite honest about wall-clock (M5 brings the
     * dedicated hammer). */
    private static final int RACE_ITERATIONS = 20;

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private DataSource dataSource;

    private static RequestPostProcessor role(String... roles) {
        return jwt().jwt(j -> j.claim("realm_access", of("roles", List.of(roles))))
                .authorities(new LedgerRealmRoleConverter());
    }

    private static RequestPostProcessor admin() {
        return role("LEDGER_ADMIN");
    }

    private static RequestPostProcessor writer(String subject) {
        return jwt().jwt(j -> j.subject(subject)
                        .claim("realm_access", of("roles", List.of("LEDGER_WRITE"))))
                .authorities(new LedgerRealmRoleConverter());
    }

    private static String subject() {
        return "lifecycle-posting-" + UUID.randomUUID();
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

    private String createAccount(String name, boolean allowNegative) {
        MvcTestResult result = mvc.post().uri("/api/v1/accounts").with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name": "%s", "currency": "EUR", "type": "ASSET", "allowNegative": %s}
                        """.formatted(name, allowNegative))
                .exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return JsonPath.read(body(result), "$.id");
    }

    private MvcTestResult patchStatus(String id, String status) {
        return mvc.patch().uri("/api/v1/accounts/{id}", id).with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\": \"%s\"}".formatted(status)).exchange();
    }

    private MvcTestResult transfer(String subject, String source, String target, long amount) {
        return mvc.post().uri("/api/v1/transfers").with(writer(subject))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"sourceAccountId": "%s", "targetAccountId": "%s",
                         "amount": {"amount": %d, "currency": "EUR"}}
                        """.formatted(source, target, amount))
                .exchange();
    }

    private MvcTestResult postJournal(String subject, String probe, long amountOnProbe,
            String counter, long amountOnCounter) {
        return mvc.post().uri("/api/v1/journal-entries").with(writer(subject))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"postings": [
                          {"accountId": "%s", "amount": {"amount": %d, "currency": "EUR"}},
                          {"accountId": "%s", "amount": {"amount": %d, "currency": "EUR"}}]}
                        """.formatted(probe, amountOnProbe, counter, amountOnCounter))
                .exchange();
    }

    private long postingCountOn(String accountId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT count(*) FROM posting WHERE account_id = ?")) {
            select.setObject(1, UUID.fromString(accountId));
            try (ResultSet row = select.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getLong(1);
            }
        }
    }

    /** I4 for the given accounts — snapshot vs SUM/COUNT in one statement (ADR-0002). */
    private void assertI4(String... accountIds) throws SQLException {
        for (String accountId : accountIds) {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement select = connection.prepareStatement("""
                         SELECT b.balance, b.posting_count,
                                COALESCE(SUM(p.amount), 0) AS posted_sum,
                                COUNT(p.id) AS posted_count
                         FROM account_balance b
                         LEFT JOIN posting p ON p.account_id = b.account_id
                         WHERE b.account_id = ?
                         GROUP BY b.balance, b.posting_count
                         """)) {
                select.setObject(1, UUID.fromString(accountId));
                try (ResultSet row = select.executeQuery()) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getLong("balance")).as("I4 balance for " + accountId)
                            .isEqualTo(row.getLong("posted_sum"));
                    assertThat(row.getLong("posting_count")).as("I4 count for " + accountId)
                            .isEqualTo(row.getLong("posted_count"));
                }
            }
        }
    }

    @Test
    @DisplayName("I12: a FROZEN account rejects postings in BOTH directions — debit leg and credit leg alike")
    void frozen_account_rejects_both_directions() throws SQLException {
        String subject = subject();
        String frozen = createAccount(marker("frz-probe"), true);
        String counter = createAccount(marker("frz-counter"), true);
        assertThat(patchStatus(frozen, "FROZEN")).hasStatusOk();

        for (long amount : List.of(50L, -50L)) {
            MvcTestResult result = postJournal(subject, frozen, amount, counter, -amount);
            assertThat(result).as("frozen leg amount " + amount)
                    .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(JsonPath.<String>read(body(result), "$.type"))
                    .isEqualTo(PROBLEMS + "account-frozen");
        }
        assertThat(postingCountOn(frozen)).isZero();
        assertI4(frozen, counter);
    }

    @Test
    @DisplayName("I12: a CLOSED account rejects postings in BOTH directions, terminally")
    void closed_account_rejects_both_directions() throws SQLException {
        String subject = subject();
        String closed = createAccount(marker("cls-probe"), true);
        String counter = createAccount(marker("cls-counter"), true);
        assertThat(patchStatus(closed, "CLOSED")).hasStatusOk();

        for (long amount : List.of(50L, -50L)) {
            MvcTestResult result = postJournal(subject, closed, amount, counter, -amount);
            assertThat(result).as("closed leg amount " + amount)
                    .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(JsonPath.<String>read(body(result), "$.type"))
                    .isEqualTo(PROBLEMS + "account-closed");
        }
        assertThat(postingCountOn(closed)).isZero();
        assertI4(closed, counter);
    }

    @Test
    @DisplayName("I12: closing a nonzero-balance account → 422 account-balance-not-zero; the account stays open")
    void close_with_nonzero_balance_is_rejected() throws SQLException {
        String subject = subject();
        String funded = createAccount(marker("nz-funded"), false);
        String counter = createAccount(marker("nz-counter"), true);
        assertThat(transfer(subject, funded, counter, 100)).hasStatus(HttpStatus.CREATED);

        MvcTestResult close = patchStatus(funded, "CLOSED");
        assertThat(close).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(JsonPath.<String>read(body(close), "$.type"))
                .isEqualTo(PROBLEMS + "account-balance-not-zero");
        assertThat(JsonPath.<String>read(body(close), "$.accountId")).isEqualTo(funded);
        // The rejected close changed nothing: still ACTIVE, still accepts postings.
        assertThat(mvc.get().uri("/api/v1/accounts/{id}", funded).with(role("LEDGER_READ")))
                .hasStatusOk().bodyJson().extractingPath("$.status").isEqualTo("ACTIVE");
        assertI4(funded, counter);
    }

    @Test
    @DisplayName("I12: drain to zero, close succeeds, then posting → 422 — the full lifecycle arc")
    void drain_close_then_post_is_rejected() throws SQLException {
        String subject = subject();
        String account = createAccount(marker("arc-main"), false);
        String counter = createAccount(marker("arc-counter"), true);
        assertThat(transfer(subject, account, counter, 100)).hasStatus(HttpStatus.CREATED);
        // Drain: the counter-transfer credits the account back to exactly zero (I1 arithmetic,
        // not a special "drain" operation — there is none).
        assertThat(transfer(subject, counter, account, 100)).hasStatus(HttpStatus.CREATED);

        assertThat(patchStatus(account, "CLOSED")).hasStatusOk();

        MvcTestResult post = postJournal(subject, account, 10, counter, -10);
        assertThat(post).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(JsonPath.<String>read(body(post), "$.type"))
                .isEqualTo(PROBLEMS + "account-closed");
        assertThat(postingCountOn(account)).as("closed with its committed history intact")
                .isEqualTo(2);
        assertI4(account, counter);
    }

    @Test
    @DisplayName("I12: bounded close-vs-post race — every interleaving lands on one of the two lock-serialized outcomes")
    void close_vs_post_race_is_always_serialized() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int iteration = 0; iteration < RACE_ITERATIONS; iteration++) {
                String subject = subject();
                String probe = createAccount(marker("race-probe"), false);
                String counter = createAccount(marker("race-counter"), true);
                CountDownLatch start = new CountDownLatch(1);
                Future<MvcTestResult> transferAttempt = pool.submit(() -> {
                    start.await();
                    return transfer(subject, probe, counter, 100);
                });
                Future<MvcTestResult> closeAttempt = pool.submit(() -> {
                    start.await();
                    return patchStatus(probe, "CLOSED");
                });
                start.countDown();
                MvcTestResult transferred = transferAttempt.get(30, TimeUnit.SECONDS);
                MvcTestResult closed = closeAttempt.get(30, TimeUnit.SECONDS);
                int transferStatus = transferred.getResponse().getStatus();
                int closeStatus = closed.getResponse().getStatus();

                if (transferStatus == 201) {
                    // Transfer won the lock: the close saw natural 100 and had to reject.
                    assertThat(closeStatus).as("iteration " + iteration).isEqualTo(422);
                    assertThat(JsonPath.<String>read(body(closed), "$.type"))
                            .isEqualTo(PROBLEMS + "account-balance-not-zero");
                } else {
                    // Close won the lock: the transfer saw CLOSED and had to reject — with the
                    // domain's 422, never a lock/serialization 5xx (ADR-0003).
                    assertThat(transferStatus).as("iteration " + iteration).isEqualTo(422);
                    assertThat(JsonPath.<String>read(body(transferred), "$.type"))
                            .isEqualTo(PROBLEMS + "account-closed");
                    assertThat(closeStatus).as("iteration " + iteration).isEqualTo(200);
                    // The invariant the race exists to threaten: no posting on a CLOSED account.
                    assertThat(postingCountOn(probe))
                            .as("iteration %d: a posting landed on a CLOSED account"
                                    .formatted(iteration))
                            .isZero();
                }
                assertI4(probe, counter);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
