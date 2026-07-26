package io.github.essandhu.ledger.adapter.web;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
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
 * I11 over HTTP: a reversal exactly negates its original leg by leg, an entry can be reversed
 * at most once, and the double-reversal race loses with a 422 — never a 5xx, because the
 * shared account-set lock serializes the check (ADR-0003) and the partial unique index
 * {@code journal_entry_reversed_once} stays a silent backstop. Reversing a REVERSAL is legal
 * by the same model (PLAN §4.1): "reversed" is a derived property, so a reversal of a reversal
 * is simply a fresh entry with a fresh {@code reversal_of} pointer at the reversal — nothing
 * about the original changes. And because a reversal is an ordinary posting, it obeys the
 * status gate: reversing into a FROZEN account fails, the documented operational caveat of
 * PLAN §4.5.
 */
@LedgerIntegrationTest
@DisplayName("I11: reversals — exact negation, at-most-once, races lose loudly")
class ReversalApiIntegrationTest {

    private static final String PROBLEMS = "https://essandhu.github.io/ledger/problems/";

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
        return "reversal-api-" + UUID.randomUUID();
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

    private void patchStatus(String id, String status) {
        assertThat(mvc.patch().uri("/api/v1/accounts/{id}", id).with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\": \"%s\"}".formatted(status))).hasStatusOk();
    }

    /** A committed 100-EUR transfer between fresh accounts — the standard reversal target. */
    private MvcTestResult transfer(String subject, String source, String target) {
        MvcTestResult result = mvc.post().uri("/api/v1/transfers").with(writer(subject))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"sourceAccountId": "%s", "targetAccountId": "%s",
                         "amount": {"amount": 100, "currency": "EUR"}}
                        """.formatted(source, target))
                .exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return result;
    }

    private MvcTestResult reverse(String subject, String entryId, String json) {
        var request = mvc.post().uri("/api/v1/journal-entries/{id}/reversal", entryId)
                .with(writer(subject))
                .header("Idempotency-Key", UUID.randomUUID().toString());
        if (json != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return request.exchange();
    }

    private long reversalCountFor(String entryId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT count(*) FROM journal_entry WHERE reversal_of = ?")) {
            select.setObject(1, UUID.fromString(entryId));
            try (ResultSet row = select.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getLong(1);
            }
        }
    }

    private record Snapshot(long balance, long postingCount) {
    }

    private Snapshot snapshot(String accountId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT balance, posting_count FROM account_balance WHERE account_id = ?")) {
            select.setObject(1, UUID.fromString(accountId));
            try (ResultSet row = select.executeQuery()) {
                assertThat(row.next()).as("snapshot row for " + accountId).isTrue();
                return new Snapshot(row.getLong("balance"), row.getLong("posting_count"));
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

    private long pairSum(String entryId, String reversalId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT COALESCE(SUM(amount), 0) FROM posting WHERE entry_id IN (?, ?)")) {
            select.setObject(1, UUID.fromString(entryId));
            select.setObject(2, UUID.fromString(reversalId));
            try (ResultSet row = select.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getLong(1);
            }
        }
    }

    @Test
    @DisplayName("I11: the reversal negates the original leg by leg — accounts and order preserved — and the pair nets zero")
    void reversal_negates_exactly_and_pair_nets_zero() throws SQLException {
        String subject = subject();
        String source = createAccount(marker("neg-src"), false);
        String target = createAccount(marker("neg-tgt"), true);
        String original = body(transfer(subject, source, target));
        String originalId = JsonPath.read(original, "$.id");

        MvcTestResult result = reverse(subject, originalId, "{\"description\": \"undo\"}");
        assertThat(result).hasStatus(HttpStatus.CREATED);
        String reversal = body(result);
        String reversalId = JsonPath.read(reversal, "$.id");
        assertThat(result.getResponse().getHeader("Location"))
                .endsWith("/api/v1/journal-entries/" + reversalId);
        assertThat(JsonPath.<String>read(reversal, "$.entryType")).isEqualTo("REVERSAL");
        assertThat(JsonPath.<String>read(reversal, "$.reversalOf")).isEqualTo(originalId);
        assertThat(JsonPath.<String>read(reversal, "$.description")).isEqualTo("undo");

        // Leg-by-leg, positionally: same accounts in the same order, every amount negated.
        assertThat(JsonPath.<List<String>>read(reversal, "$.postings[*].accountId"))
                .containsExactlyElementsOf(JsonPath.read(original, "$.postings[*].accountId"));
        List<Integer> originalAmounts = JsonPath.read(original, "$.postings[*].amount.amount");
        assertThat(JsonPath.<List<Integer>>read(reversal, "$.postings[*].amount.amount"))
                .containsExactlyElementsOf(originalAmounts.stream().map(a -> -a).toList());
        assertThat(JsonPath.<List<String>>read(reversal, "$.postings[*].amount.currency"))
                .containsExactlyElementsOf(JsonPath.read(original, "$.postings[*].amount.currency"));

        // The pair nets zero at rest, and the snapshots are back where they started — with the
        // posting count remembering both entries (history is appended, never unwound — I3).
        assertThat(pairSum(originalId, reversalId)).isZero();
        assertThat(snapshot(source)).isEqualTo(new Snapshot(0, 2));
        assertThat(snapshot(target)).isEqualTo(new Snapshot(0, 2));
        assertI4(source, target);
    }

    @Test
    @DisplayName("I11: reversing a reversal is LEGAL — a fresh reversal_of pointer at the reversal, restoring the original postings")
    void reversing_a_reversal_is_legal() throws SQLException {
        String subject = subject();
        String source = createAccount(marker("rr-src"), false);
        String target = createAccount(marker("rr-tgt"), true);
        String original = body(transfer(subject, source, target));
        String originalId = JsonPath.read(original, "$.id");

        String firstReversalId = JsonPath.read(
                body(reverse(subject, originalId, null)), "$.id");
        MvcTestResult second = reverse(subject, firstReversalId, null);
        // The partial unique index constrains each entry to AT MOST ONE reversal; the first
        // reversal has none yet, so reversing it is an ordinary post (PLAN §4.1's model:
        // "reversed" is derived, nothing on the original — or the reversal — ever mutates).
        assertThat(second).hasStatus(HttpStatus.CREATED);
        String secondReversal = body(second);
        assertThat(JsonPath.<String>read(secondReversal, "$.reversalOf"))
                .isEqualTo(firstReversalId);
        // Negation of the negation: the second reversal restores the original's exact legs.
        assertThat(JsonPath.<List<Integer>>read(secondReversal, "$.postings[*].amount.amount"))
                .containsExactlyElementsOf(JsonPath.read(original, "$.postings[*].amount.amount"));

        assertThat(reversalCountFor(originalId)).isEqualTo(1);
        assertThat(reversalCountFor(firstReversalId)).isEqualTo(1);
        assertThat(snapshot(source)).isEqualTo(new Snapshot(100, 3));
        assertThat(snapshot(target)).isEqualTo(new Snapshot(-100, 3));
        assertI4(source, target);
    }

    @Test
    @DisplayName("I11: a second reversal of the same entry → 422 entry-already-reversed, nothing written")
    void second_reversal_is_rejected() throws SQLException {
        String subject = subject();
        String source = createAccount(marker("dup-src"), false);
        String target = createAccount(marker("dup-tgt"), true);
        String originalId = JsonPath.read(body(transfer(subject, source, target)), "$.id");
        assertThat(reverse(subject, originalId, null)).hasStatus(HttpStatus.CREATED);
        Snapshot sourceAfter = snapshot(source);
        Snapshot targetAfter = snapshot(target);

        MvcTestResult retry = reverse(subject, originalId, null);
        assertThat(retry).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(JsonPath.<String>read(body(retry), "$.type"))
                .isEqualTo(PROBLEMS + "entry-already-reversed");
        assertThat(reversalCountFor(originalId)).isEqualTo(1);
        assertThat(snapshot(source)).isEqualTo(sourceAfter);
        assertThat(snapshot(target)).isEqualTo(targetAfter);
        assertI4(source, target);
    }

    @Test
    @DisplayName("I11: two threads racing to reverse the same entry — exactly one reversal row ever; the loser gets 422, never a 5xx")
    void concurrent_double_reversal_leaves_exactly_one_row() throws Exception {
        String subject = subject();
        String source = createAccount(marker("race-src"), false);
        String target = createAccount(marker("race-tgt"), true);
        String originalId = JsonPath.read(body(transfer(subject, source, target)), "$.id");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Callable<MvcTestResult> attempt = () -> {
                start.await();
                return reverse(subject, originalId, null);
            };
            Future<MvcTestResult> first = pool.submit(attempt);
            Future<MvcTestResult> second = pool.submit(attempt);
            start.countDown();
            MvcTestResult a = first.get(30, TimeUnit.SECONDS);
            MvcTestResult b = second.get(30, TimeUnit.SECONDS);

            // The account-set lock serializes the two: the loser's in-lock check finds the
            // winner's committed reversal and rejects with the DOMAIN error (ADR-0003: rejected
            // requests fail with a domain error, never a lock/serialization/constraint error).
            List<Integer> statuses = List.of(a.getResponse().getStatus(), b.getResponse().getStatus());
            assertThat(statuses).containsExactlyInAnyOrder(201, 422);
            MvcTestResult loser = a.getResponse().getStatus() == 422 ? a : b;
            assertThat(JsonPath.<String>read(body(loser), "$.type"))
                    .isEqualTo(PROBLEMS + "entry-already-reversed");
            assertThat(reversalCountFor(originalId)).isEqualTo(1);
            assertI4(source, target);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("I11 × I12: a reversal touching a FROZEN account → 422 account-frozen — the documented operational caveat")
    void reversal_touching_frozen_account_is_rejected() throws SQLException {
        String subject = subject();
        String source = createAccount(marker("frz-src"), false);
        String target = createAccount(marker("frz-tgt"), true);
        String originalId = JsonPath.read(body(transfer(subject, source, target)), "$.id");
        patchStatus(target, "FROZEN");
        Snapshot sourceBefore = snapshot(source);
        Snapshot targetBefore = snapshot(target);

        MvcTestResult result = reverse(subject, originalId, null);
        assertThat(result).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(JsonPath.<String>read(body(result), "$.type"))
                .isEqualTo(PROBLEMS + "account-frozen");
        assertThat(reversalCountFor(originalId)).isZero();
        assertThat(snapshot(source)).isEqualTo(sourceBefore);
        assertThat(snapshot(target)).isEqualTo(targetBefore);
        assertI4(source, target);
    }
}
