package io.github.essandhu.ledger.adapter.web;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import io.github.essandhu.ledger.config.LedgerRealmRoleConverter;
import io.github.essandhu.ledger.support.LedgerIntegrationTest;

import static java.util.Map.of;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/**
 * I8 (serial) and I9 over the HTTP surface against real PostgreSQL (ADR-0004, PLAN §5): replay
 * answers 200 with the STORED original body byte for byte plus {@code Idempotency-Replayed:
 * true}; conflict answers 422 {@code idempotency-key-conflict} with zero side effects; the
 * concurrent duplicate serializes on the database and never double-posts. Key shape and the
 * required header are the 400 family. Shared-context discipline as everywhere: unique subjects
 * and marker accounts per scenario, no assertion quantifies over rows the test did not create.
 */
@LedgerIntegrationTest
@DisplayName("Idempotency API (M4): I8 replay, I9 conflict, key shape, concurrent duplicates")
class IdempotencyApiIntegrationTest {

    private static final String PROBLEMS = "https://essandhu.github.io/ledger/problems/";
    private static final String REPLAYED_HEADER = "Idempotency-Replayed";

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private MeterRegistry meterRegistry;

    private static RequestPostProcessor admin() {
        return jwt().jwt(j -> j.claim("realm_access", of("roles", List.of("LEDGER_ADMIN"))))
                .authorities(new LedgerRealmRoleConverter());
    }

    private static RequestPostProcessor writer(String subject) {
        return jwt().jwt(j -> j.subject(subject)
                        .claim("realm_access", of("roles", List.of("LEDGER_WRITE"))))
                .authorities(new LedgerRealmRoleConverter());
    }

    private static String subject() {
        return "idem-api-" + UUID.randomUUID();
    }

    private static String key() {
        return "idem-key-" + UUID.randomUUID();
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

    private static String transferJson(String source, String target, long amount) {
        return """
                {"sourceAccountId": "%s", "targetAccountId": "%s",
                 "amount": {"amount": %d, "currency": "EUR"}}
                """.formatted(source, target, amount);
    }

    private static String journalJson(String a, String b, long amount) {
        return """
                {"description": null, "postings": [
                  {"accountId": "%s", "amount": {"amount": %d, "currency": "EUR"}},
                  {"accountId": "%s", "amount": {"amount": %d, "currency": "EUR"}}]}
                """.formatted(a, amount, b, -amount);
    }

    private MvcTestResult postTransfer(String subject, String key, String json) {
        return mvc.post().uri("/api/v1/transfers").with(writer(subject))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(json).exchange();
    }

    private MvcTestResult postJournal(String subject, String key, String json) {
        return mvc.post().uri("/api/v1/journal-entries").with(writer(subject))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(json).exchange();
    }

    private MvcTestResult postReversal(String subject, String key, String entryId) {
        return mvc.post().uri("/api/v1/journal-entries/{id}/reversal", entryId)
                .with(writer(subject))
                .header("Idempotency-Key", key)
                .exchange();
    }

    private long naturalBalance(String accountId) {
        MvcTestResult result = mvc.get().uri("/api/v1/accounts/{id}/balance", accountId)
                .with(jwt().jwt(j -> j.claim("realm_access", of("roles", List.of("LEDGER_READ"))))
                        .authorities(new LedgerRealmRoleConverter()))
                .exchange();
        assertThat(result).hasStatusOk();
        return ((Number) JsonPath.read(body(result), "$.balance.amount")).longValue();
    }

    private long entryCount(String subject) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT count(*) FROM journal_entry WHERE created_by = ?")) {
            select.setString(1, subject);
            try (ResultSet row = select.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getLong(1);
            }
        }
    }

    private record StoredRecord(String requestHash, UUID entryId, int responseStatus,
            String responseBody, Instant createdAt, Instant expiresAt) {
    }

    private StoredRecord storedRecord(String subject, String key) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement("""
                     SELECT request_hash, entry_id, response_status, response_body,
                            created_at, expires_at
                     FROM idempotency_record WHERE created_by = ? AND idem_key = ?
                     """)) {
            select.setString(1, subject);
            select.setString(2, key);
            try (ResultSet row = select.executeQuery()) {
                assertThat(row.next()).as("idempotency record for " + key).isTrue();
                return new StoredRecord(row.getString("request_hash"),
                        row.getObject("entry_id", UUID.class), row.getInt("response_status"),
                        row.getString("response_body"),
                        row.getObject("created_at", java.time.OffsetDateTime.class).toInstant(),
                        row.getObject("expires_at", java.time.OffsetDateTime.class).toInstant());
            }
        }
    }

    @Nested
    @DisplayName("I8 (serial): replay returns the original, and only one entry ever exists")
    class Replay {

        @Test
        @DisplayName("transfer replay: 200, Idempotency-Replayed: true, byte-identical body, balances moved once, record row proven")
        void transfer_replay_is_byte_identical_and_side_effect_free() throws SQLException {
            String subject = subject();
            String key = key();
            // The target is CREDITED (−250 raw), so it must allow a negative natural balance.
            String source = createAccount(marker("rp-src"), false);
            String target = createAccount(marker("rp-tgt"), true);
            String json = transferJson(source, target, 250);

            MvcTestResult first = postTransfer(subject, key, json);
            assertThat(first).hasStatus(HttpStatus.CREATED);
            String firstBody = body(first);
            String entryId = JsonPath.read(firstBody, "$.id");
            assertThat(first.getResponse().getHeader(REPLAYED_HEADER))
                    .as("a fresh post is not marked as a replay").isNull();

            MvcTestResult replay = postTransfer(subject, key, json);
            assertThat(replay).hasStatusOk()
                    .hasContentTypeCompatibleWith(MediaType.APPLICATION_JSON);
            assertThat(replay.getResponse().getHeader(REPLAYED_HEADER)).isEqualTo("true");
            assertThat(replay.getResponse().getHeader("Location"))
                    .as("nothing was created by a replay — no Location (PLAN §5 M4 pin)")
                    .isNull();
            assertThat(body(replay))
                    .as("the STORED original response, byte for byte (ADR-0004)")
                    .isEqualTo(firstBody);

            assertThat(entryCount(subject)).as("one entry ever (I8)").isEqualTo(1);
            assertThat(naturalBalance(source))
                    .as("balances changed exactly once — via the M3 query surface")
                    .isEqualTo(250);
            assertThat(naturalBalance(target)).isEqualTo(-250);

            StoredRecord record = storedRecord(subject, key);
            assertThat(record.requestHash()).matches("[0-9a-f]{64}");
            assertThat(record.entryId()).isEqualTo(UUID.fromString(entryId));
            assertThat(record.responseStatus()).isEqualTo(201);
            assertThat(record.responseBody())
                    .as("text column stores the body verbatim — jsonb would re-order keys")
                    .isEqualTo(firstBody);
            assertThat(record.expiresAt())
                    .as("expires_at = created_at + the default 90-day TTL (ADR-0004)")
                    .isEqualTo(record.createdAt().plus(Duration.ofDays(90)));
        }

        @Test
        @DisplayName("no false conflicts: a retry with reordered JSON fields and different whitespace REPLAYS — the hash sees the parsed command (ADR-0004 option 2c)")
        void reordered_wire_form_replays_rather_than_conflicts() throws SQLException {
            String subject = subject();
            String key = key();
            String source = createAccount(marker("wire-src"), true);
            String target = createAccount(marker("wire-tgt"), true);

            MvcTestResult first = postTransfer(subject, key, transferJson(source, target, 99));
            assertThat(first).hasStatus(HttpStatus.CREATED);

            // The same COMMAND as a different HTTP client would serialize it: fields
            // reordered, whitespace collapsed, an explicit null description. A raw-bytes hash
            // (option 2b) would reject this legitimate retry as a conflict — and the client's
            // documented recovery from that error (mint a new key, resend) would double-post.
            String reordered = ("{\"description\":null,\"amount\":{\"currency\":\"EUR\","
                    + "\"amount\":99},\"targetAccountId\":\"%s\",\"sourceAccountId\":\"%s\"}")
                    .formatted(target, source);
            MvcTestResult replay = postTransfer(subject, key, reordered);

            assertThat(replay).hasStatusOk();
            assertThat(replay.getResponse().getHeader(REPLAYED_HEADER)).isEqualTo("true");
            assertThat(body(replay)).isEqualTo(body(first));
            assertThat(entryCount(subject)).isEqualTo(1);
        }

        @Test
        @DisplayName("journal and reversal replays carry the same semantics — one entry each, replay marked")
        void journal_and_reversal_replays_work_end_to_end() throws SQLException {
            String subject = subject();
            String a = createAccount(marker("jr-a"), true);
            String b = createAccount(marker("jr-b"), true);

            String journalKey = key();
            MvcTestResult journal = postJournal(subject, journalKey, journalJson(a, b, 40));
            assertThat(journal).hasStatus(HttpStatus.CREATED);
            String entryId = JsonPath.read(body(journal), "$.id");
            MvcTestResult journalReplay = postJournal(subject, journalKey, journalJson(a, b, 40));
            assertThat(journalReplay).hasStatusOk();
            assertThat(journalReplay.getResponse().getHeader(REPLAYED_HEADER)).isEqualTo("true");
            assertThat(body(journalReplay)).isEqualTo(body(journal));

            String reversalKey = key();
            MvcTestResult reversal = postReversal(subject, reversalKey, entryId);
            assertThat(reversal).hasStatus(HttpStatus.CREATED);
            MvcTestResult reversalReplay = postReversal(subject, reversalKey, entryId);
            assertThat(reversalReplay).hasStatusOk();
            assertThat(reversalReplay.getResponse().getHeader(REPLAYED_HEADER)).isEqualTo("true");
            assertThat(body(reversalReplay)).isEqualTo(body(reversal));
            // The replay did NOT re-execute: a second reversal would have been the 422
            // entry-already-reversed, and only two entries exist (original + one reversal).
            assertThat(entryCount(subject)).isEqualTo(2);
        }

        @Test
        @DisplayName("ADR-0004 scope 1b: the same key under different principals is two scopes — both post")
        void same_key_different_principals_both_post() throws SQLException {
            String alice = subject();
            String bob = subject();
            String sharedKey = key();
            String source = createAccount(marker("scope-src"), true);
            String target = createAccount(marker("scope-tgt"), true);

            assertThat(postTransfer(alice, sharedKey, transferJson(source, target, 10)))
                    .hasStatus(HttpStatus.CREATED);
            assertThat(postTransfer(bob, sharedKey, transferJson(source, target, 10)))
                    .as("a shared keyspace would replay ALICE's response to BOB — scope 1a's "
                            + "cross-tenant disclosure, impossible by construction here")
                    .hasStatus(HttpStatus.CREATED);
            assertThat(entryCount(alice)).isEqualTo(1);
            assertThat(entryCount(bob)).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("I9: same key, different payload → 422 conflict, zero side effects")
    class Conflict {

        @Test
        @DisplayName("tampered replay: 422 idempotency-key-conflict, the key echoed, nothing written")
        void tampered_replay_conflicts_with_zero_side_effects() throws SQLException {
            String subject = subject();
            String key = key();
            String source = createAccount(marker("cf-src"), true);
            String target = createAccount(marker("cf-tgt"), true);

            assertThat(postTransfer(subject, key, transferJson(source, target, 100)))
                    .hasStatus(HttpStatus.CREATED);
            long balanceAfterFirst = naturalBalance(target);

            MvcTestResult conflict = postTransfer(subject, key, transferJson(source, target, 999));
            assertThat(conflict).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                    .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
            assertThat(JsonPath.<String>read(body(conflict), "$.type"))
                    .isEqualTo(PROBLEMS + "idempotency-key-conflict");
            assertThat(JsonPath.<String>read(body(conflict), "$.idempotencyKey")).isEqualTo(key);

            assertThat(entryCount(subject)).as("nothing was posted for the conflict").isEqualTo(1);
            assertThat(naturalBalance(target)).isEqualTo(balanceAfterFirst);
        }

        @Test
        @DisplayName("the conflict outranks validation: a recorded key with an INVALID different payload is the conflict 422, not the validation 422")
        void conflict_precedes_draft_validation() throws SQLException {
            String subject = subject();
            String key = key();
            String a = createAccount(marker("cv-a"), true);
            String b = createAccount(marker("cv-b"), true);

            assertThat(postJournal(subject, key, journalJson(a, b, 50)))
                    .hasStatus(HttpStatus.CREATED);

            // Unbalanced AND under a taken key: the response names the client bug that
            // explains everything — the key reuse — per the use-case ordering pin.
            String unbalanced = """
                    {"description": null, "postings": [
                      {"accountId": "%s", "amount": {"amount": 70, "currency": "EUR"}},
                      {"accountId": "%s", "amount": {"amount": -30, "currency": "EUR"}}]}
                    """.formatted(a, b);
            MvcTestResult conflict = postJournal(subject, key, unbalanced);
            assertThat(conflict).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(JsonPath.<String>read(body(conflict), "$.type"))
                    .isEqualTo(PROBLEMS + "idempotency-key-conflict");
            assertThat(entryCount(subject)).isEqualTo(1);
        }

        @Test
        @DisplayName("ADR-0004 option 1b: one keyspace across endpoints — a transfer's key reused on /journal-entries conflicts, never double-posts")
        void cross_endpoint_key_reuse_conflicts() throws SQLException {
            String subject = subject();
            String key = key();
            String source = createAccount(marker("xe-src"), true);
            String target = createAccount(marker("xe-tgt"), true);

            assertThat(postTransfer(subject, key, transferJson(source, target, 75)))
                    .hasStatus(HttpStatus.CREATED);

            // The SAME logical operation expressed as an explicit journal entry: different
            // command type, different canonical form, different hash — a loud 422 instead of
            // a silent second entry.
            MvcTestResult conflict = postJournal(subject, key, journalJson(source, target, 75));
            assertThat(conflict).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(JsonPath.<String>read(body(conflict), "$.type"))
                    .isEqualTo(PROBLEMS + "idempotency-key-conflict");
            assertThat(entryCount(subject)).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("400 family: the header is required and shaped")
    class KeyShape {

        @Test
        @DisplayName("missing Idempotency-Key → 400 problem on all three write endpoints, nothing written")
        void missing_header_is_400_everywhere() throws SQLException {
            String subject = subject();
            String a = createAccount(marker("mh-a"), true);
            String b = createAccount(marker("mh-b"), true);

            List<MvcTestResult> results = List.of(
                    mvc.post().uri("/api/v1/journal-entries").with(writer(subject))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(journalJson(a, b, 10)).exchange(),
                    mvc.post().uri("/api/v1/transfers").with(writer(subject))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(transferJson(a, b, 10)).exchange(),
                    mvc.post().uri("/api/v1/journal-entries/{id}/reversal", UUID.randomUUID())
                            .with(writer(subject)).exchange());
            for (MvcTestResult result : results) {
                assertThat(result).as(result.getRequest().getRequestURI())
                        .hasStatus(HttpStatus.BAD_REQUEST)
                        .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
                assertThat(body(result))
                        .as("shape violations are BARE problems — no typed slug")
                        .doesNotContain(PROBLEMS);
            }
            assertThat(entryCount(subject)).isZero();
        }

        @Test
        @DisplayName("blank, oversized, control-character, and comma keys → bare 400 (shape violations carry no typed slug)")
        void malformed_keys_are_400() throws SQLException {
            String subject = subject();
            String a = createAccount(marker("mk-a"), true);
            String b = createAccount(marker("mk-b"), true);

            // The comma case is the doubled-header shape: HTTP joins duplicate Idempotency-Key
            // fields with commas (RFC 9110), and a key recorded as "K,K" would miss its replay
            // once an intermediary dedupes the retry to "K" — rejected loudly instead.
            for (String badKey : List.of("   ", "x".repeat(201), "bad\tkey", "K,K")) {
                MvcTestResult result = postTransfer(subject, badKey, transferJson(a, b, 10));
                // Bare problem: no typed slug — Spring omits the implied about:blank type
                // entirely, same as every other shape violation in this API.
                assertThat(result).as("key '%s'".formatted(badKey))
                        .hasStatus(HttpStatus.BAD_REQUEST)
                        .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
                assertThat(body(result)).doesNotContain(PROBLEMS);
            }
            assertThat(entryCount(subject)).isZero();
        }

        @Test
        @DisplayName("a 200-char key is legal (the pinned maximum), and replays like any other")
        void max_length_key_is_legal() {
            String subject = subject();
            String maxKey = "k".repeat(200);
            String a = createAccount(marker("mx-a"), true);
            String b = createAccount(marker("mx-b"), true);

            assertThat(postTransfer(subject, maxKey, transferJson(a, b, 5)))
                    .hasStatus(HttpStatus.CREATED);
            MvcTestResult replay = postTransfer(subject, maxKey, transferJson(a, b, 5));
            assertThat(replay).hasStatusOk();
            assertThat(replay.getResponse().getHeader(REPLAYED_HEADER)).isEqualTo("true");
        }
    }

    @Nested
    @DisplayName("PLAN §8: the idempotency counters — and what they deliberately do NOT touch")
    class Metrics {

        private double counterValue(String name) {
            Counter counter = meterRegistry.find(name).counter();
            return counter == null ? 0 : counter.count();
        }

        private long durationCount(String outcome) {
            io.micrometer.core.instrument.Timer timer =
                    meterRegistry.find("ledger.posting.duration")
                            .tags("entry_type", "TRANSFER", "outcome", outcome).timer();
            return timer == null ? 0 : timer.count();
        }

        @Test
        @DisplayName("replays and conflicts bump their own counters — and discard the duration sample, and stay out of the rejected vocabulary (PLAN §8 M4 pins)")
        void idempotency_counters_are_recorded_and_posting_series_stay_clean() {
            String subject = subject();
            String key = key();
            String source = createAccount(marker("mt-src"), true);
            String target = createAccount(marker("mt-tgt"), true);
            double replayedBefore = counterValue("ledger.idempotency.replayed");
            double conflictBefore = counterValue("ledger.idempotency.conflict");

            assertThat(postTransfer(subject, key, transferJson(source, target, 20)))
                    .hasStatus(HttpStatus.CREATED);
            long postedAfterCreate = durationCount("posted");
            long rejectedAfterCreate = durationCount("rejected");

            assertThat(postTransfer(subject, key, transferJson(source, target, 20)))
                    .hasStatusOk();
            assertThat(counterValue("ledger.idempotency.replayed"))
                    .isEqualTo(replayedBefore + 1);

            assertThat(postTransfer(subject, key, transferJson(source, target, 21)))
                    .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(counterValue("ledger.idempotency.conflict"))
                    .isEqualTo(conflictBefore + 1);

            // The pinned negatives: nothing was posted or domain-rejected by the replay or
            // the conflict, so the duration series gained NO sample from either — and the
            // conflict has no reason tag in the rejected vocabulary (its counter is the
            // dedicated one, the account-balance-not-zero exclusion precedent).
            assertThat(durationCount("posted"))
                    .as("a replay is not a posting — sample discarded")
                    .isEqualTo(postedAfterCreate);
            assertThat(durationCount("rejected"))
                    .as("a conflict is not a posting rejection — sample discarded")
                    .isEqualTo(rejectedAfterCreate);
            assertThat(meterRegistry.find("ledger.posting.rejected")
                    .tags("reason", "idempotency-key-conflict").counter())
                    .as("idempotency-key-conflict never joins the rejected reason vocabulary")
                    .isNull();
        }
    }

    @Nested
    @DisplayName("concurrent duplicate (ADR-0004): the database arbitrates; M5 hammers this racier")
    class ConcurrentDuplicate {

        @Test
        @DisplayName("two simultaneous requests, same (principal, key, payload): one 201, one 200-replay, exactly one entry")
        void simultaneous_same_key_requests_yield_one_entry() throws Exception {
            String subject = subject();
            String key = key();
            String source = createAccount(marker("race-src"), true);
            String target = createAccount(marker("race-tgt"), true);
            String json = transferJson(source, target, 60);

            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                CountDownLatch start = new CountDownLatch(1);
                List<Future<MvcTestResult>> futures = List.of(
                        executor.submit(() -> {
                            start.await();
                            return postTransfer(subject, key, json);
                        }),
                        executor.submit(() -> {
                            start.await();
                            return postTransfer(subject, key, json);
                        }));
                start.countDown();
                MvcTestResult one = futures.get(0).get(30, TimeUnit.SECONDS);
                MvcTestResult two = futures.get(1).get(30, TimeUnit.SECONDS);

                List<Integer> statuses = List.of(one.getResponse().getStatus(),
                        two.getResponse().getStatus());
                assertThat(statuses).containsExactlyInAnyOrder(201, 200);
                MvcTestResult replayed = one.getResponse().getStatus() == 200 ? one : two;
                MvcTestResult created = one.getResponse().getStatus() == 201 ? one : two;
                assertThat(replayed.getResponse().getHeader(REPLAYED_HEADER)).isEqualTo("true");
                assertThat(body(replayed))
                        .as("the loser serves the winner's stored response")
                        .isEqualTo(body(created));
            } finally {
                executor.shutdownNow();
            }
            assertThat(entryCount(subject)).as("exactly one entry, however the race fell")
                    .isEqualTo(1);
            assertThat(naturalBalance(target)).as("money moved exactly once").isEqualTo(-60);
        }

        @Test
        @DisplayName("duplicate REVERSALS under one key: the loser replays — never 422 entry-already-reversed (the under-lock re-read)")
        void simultaneous_same_key_reversals_replay_never_already_reversed() throws Exception {
            // Without the under-lock idempotency re-read, the loser would re-run the
            // at-most-once check against the winner's committed reversal and answer a 422
            // for an operation that SUCCEEDED. The deterministic proof is the unit test
            // (PostingServiceTest.Idempotency); this drives the same ending over HTTP.
            String subject = subject();
            String a = createAccount(marker("rrace-a"), true);
            String b = createAccount(marker("rrace-b"), true);
            MvcTestResult posted = postJournal(subject, key(), journalJson(a, b, 30));
            assertThat(posted).hasStatus(HttpStatus.CREATED);
            String entryId = JsonPath.read(body(posted), "$.id");
            String reversalKey = key();

            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                CountDownLatch start = new CountDownLatch(1);
                List<Future<MvcTestResult>> futures = List.of(
                        executor.submit(() -> {
                            start.await();
                            return postReversal(subject, reversalKey, entryId);
                        }),
                        executor.submit(() -> {
                            start.await();
                            return postReversal(subject, reversalKey, entryId);
                        }));
                start.countDown();
                MvcTestResult one = futures.get(0).get(30, TimeUnit.SECONDS);
                MvcTestResult two = futures.get(1).get(30, TimeUnit.SECONDS);

                assertThat(List.of(one.getResponse().getStatus(), two.getResponse().getStatus()))
                        .as("one creation, one replay — a domain 422 here means the "
                                + "duplicate was misfiled")
                        .containsExactlyInAnyOrder(201, 200);
                MvcTestResult replayed = one.getResponse().getStatus() == 200 ? one : two;
                assertThat(replayed.getResponse().getHeader(REPLAYED_HEADER)).isEqualTo("true");
            } finally {
                executor.shutdownNow();
            }
            assertThat(entryCount(subject)).as("original + exactly one reversal").isEqualTo(2);
        }

        @Test
        @DisplayName("duplicate transfers draining a strict account: the loser replays — never 422 overdraft against post-winner state")
        void simultaneous_same_key_transfers_replay_never_overdraft() throws Exception {
            String subject = subject();
            // The overdraft side of a transfer is the CREDITED target (−amount raw, PLAN §5).
            // A strict target funded with EXACTLY the transferred amount: the winner drains
            // it to natural 0, and re-judging the duplicate against that state would file it
            // as an overdraft — the failure-mode inversion ADR-0004 warns about.
            String funder = createAccount(marker("orace-funder"), true);
            String source = createAccount(marker("orace-src"), true);
            String drain = createAccount(marker("orace-drain"), false);
            assertThat(postJournal(subject, key(), journalJson(drain, funder, 80)))
                    .as("fund the strict target: +80 raw (ASSET natural +80)")
                    .hasStatus(HttpStatus.CREATED);
            String raceKey = key();
            String json = transferJson(source, drain, 80);

            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                CountDownLatch start = new CountDownLatch(1);
                List<Future<MvcTestResult>> futures = List.of(
                        executor.submit(() -> {
                            start.await();
                            return postTransfer(subject, raceKey, json);
                        }),
                        executor.submit(() -> {
                            start.await();
                            return postTransfer(subject, raceKey, json);
                        }));
                start.countDown();
                MvcTestResult one = futures.get(0).get(30, TimeUnit.SECONDS);
                MvcTestResult two = futures.get(1).get(30, TimeUnit.SECONDS);

                assertThat(List.of(one.getResponse().getStatus(), two.getResponse().getStatus()))
                        .as("one creation, one replay — a 422 overdraft here means the "
                                + "duplicate was re-judged against post-winner state")
                        .containsExactlyInAnyOrder(201, 200);
            } finally {
                executor.shutdownNow();
            }
            assertThat(naturalBalance(drain)).as("drained exactly once, to zero").isZero();
        }
    }
}
