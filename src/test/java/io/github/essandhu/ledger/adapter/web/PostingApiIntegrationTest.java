package io.github.essandhu.ledger.adapter.web;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

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
import io.micrometer.core.instrument.Timer;

import io.github.essandhu.ledger.config.LedgerRealmRoleConverter;
import io.github.essandhu.ledger.support.LedgerIntegrationTest;

import static java.util.Map.of;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/**
 * The posting HTTP surface (PLAN §5) against real PostgreSQL: journal entries, transfers,
 * reads, and every 422 rejection reason with its pinned problem type URI — except
 * {@code entry-already-reversed} (proven with the rest of I11 in
 * {@link ReversalApiIntegrationTest}) and {@code account-balance-not-zero} (the close use
 * case's rejection, proven in {@link LifecycleVsPostingIntegrationTest}).
 *
 * <p>Every scenario re-proves I4 (snapshot = SUM(amount), posting_count = COUNT(*), raw JDBC in
 * one statement — ADR-0002's reconciliation shape) and I5 (per-currency zero-sum over this
 * test's own accounts) after it runs; every rejection additionally proves the ADR-0004
 * write-nothing contract: no entry row, no posting rows, snapshots untouched. Shared-context
 * discipline: rows carry unique marker names, entries a unique JWT subject per scenario (the
 * {@code created_by} column makes entry counting additive-safe), and no assertion quantifies
 * over rows the test did not create.
 */
@LedgerIntegrationTest
@DisplayName("Posting API (M2): entries, transfers, RFC 9457 rejections, I4/I5 by SQL")
class PostingApiIntegrationTest {

    private static final String PROBLEMS = "https://essandhu.github.io/ledger/problems/";

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private MeterRegistry meterRegistry;

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

    /** LEDGER_WRITE with a chosen JWT subject — the subject becomes created_by (PLAN §7). */
    private static RequestPostProcessor writer(String subject) {
        return jwt().jwt(j -> j.subject(subject)
                        .claim("realm_access", of("roles", List.of("LEDGER_WRITE"))))
                .authorities(new LedgerRealmRoleConverter());
    }

    /**
     * LEDGER_WRITE whose JWT carries NO {@code sub} claim — legal per RFC 7519 (sub is
     * optional), mintable by a misconfigured issuer, and unusable here: created_by requires a
     * subject (PLAN §7). The mock-JWT route deliberately bypasses the decoder, so this exercises
     * the controller-level guard — the only layer these tests can prove.
     */
    private static RequestPostProcessor subjectlessWriter() {
        return jwt().jwt(j -> j.claims(claims -> claims.remove("sub"))
                        .claim("realm_access", of("roles", List.of("LEDGER_WRITE"))))
                .authorities(new LedgerRealmRoleConverter());
    }

    private static String subject() {
        return "posting-api-" + UUID.randomUUID();
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

    // ── fixtures over the account API (the M1 surface is this test's setup tool) ───────────

    private String createAccount(String name, String currency, String type, boolean allowNegative) {
        MvcTestResult result = mvc.post().uri("/api/v1/accounts").with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name": "%s", "currency": "%s", "type": "%s", "allowNegative": %s}
                        """.formatted(name, currency, type, allowNegative))
                .exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return JsonPath.read(body(result), "$.id");
    }

    private void patchStatus(String id, String status) {
        assertThat(mvc.patch().uri("/api/v1/accounts/{id}", id).with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\": \"%s\"}".formatted(status))).hasStatusOk();
    }

    /** M4: every write requires Idempotency-Key; these helpers mint a fresh UUID key per call
     * (the recommended client behavior), so pre-M4 scenarios keep meaning "distinct logical
     * operations". Replay/conflict semantics get their own suite (IdempotencyApiIntegrationTest). */
    private MvcTestResult postJournal(String subject, String json) {
        return mvc.post().uri("/api/v1/journal-entries").with(writer(subject))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(json).exchange();
    }

    private MvcTestResult postTransfer(String subject, String json) {
        return mvc.post().uri("/api/v1/transfers").with(writer(subject))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(json).exchange();
    }

    private static String leg(String accountId, long amount, String currency) {
        return "{\"accountId\": \"%s\", \"amount\": {\"amount\": %d, \"currency\": \"%s\"}}"
                .formatted(accountId, amount, currency);
    }

    private static String journal(String description, String... legs) {
        return "{\"description\": %s, \"postings\": [%s]}".formatted(
                description == null ? "null" : "\"" + description + "\"", String.join(", ", legs));
    }

    private static String transfer(String sourceId, String targetId, long amount, String currency) {
        return """
                {"sourceAccountId": "%s", "targetAccountId": "%s",
                 "amount": {"amount": %d, "currency": "%s"}}
                """.formatted(sourceId, targetId, amount, currency);
    }

    // ── raw-JDBC invariant probes (I4, I5, ADR-0004 write-nothing) ─────────────────────────

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

    private long postingCount(List<String> accountIds) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT count(*) FROM posting WHERE account_id = ANY (?)")) {
            select.setArray(1, connection.createArrayOf(
                    "uuid", accountIds.stream().map(UUID::fromString).toArray()));
            try (ResultSet row = select.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getLong(1);
            }
        }
    }

    /**
     * I4 per account, in ONE statement — the same (balance, posting_count) vs
     * (SUM(amount), COUNT(*)) comparison M6 reconciliation will run (ADR-0002).
     */
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
                    assertThat(row.next()).as("snapshot row for " + accountId).isTrue();
                    assertThat(row.getLong("balance"))
                            .as("I4: snapshot balance = SUM(amount) for " + accountId)
                            .isEqualTo(row.getLong("posted_sum"));
                    assertThat(row.getLong("posting_count"))
                            .as("I4: posting_count = COUNT(*) for " + accountId)
                            .isEqualTo(row.getLong("posted_count"));
                }
            }
        }
    }

    /**
     * I5 scoped additively: every entry this test creates touches ONLY its own marker accounts,
     * so per-currency zero over their postings is exactly global conservation restricted to the
     * rows this test is entitled to quantify over (TEST-STRATEGY §2).
     */
    private void assertI5(List<String> accountIds) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement("""
                     SELECT currency, SUM(amount) AS total
                     FROM posting
                     WHERE account_id = ANY (?)
                     GROUP BY currency
                     """)) {
            select.setArray(1, connection.createArrayOf(
                    "uuid", accountIds.stream().map(UUID::fromString).toArray()));
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    assertThat(rows.getLong("total"))
                            .as("I5: %s must net to zero".formatted(rows.getString("currency")))
                            .isZero();
                }
            }
        }
    }

    /** ADR-0004: a rejected posting writes NOTHING — no entry, no legs, snapshots untouched. */
    private void assertNothingWritten(String subject, List<String> accountIds,
            List<Snapshot> snapshotsBefore) throws SQLException {
        assertThat(entryCount(subject)).as("rejected request recorded an entry").isZero();
        assertThat(postingCount(accountIds)).as("rejected request wrote postings").isZero();
        for (int i = 0; i < accountIds.size(); i++) {
            assertThat(snapshot(accountIds.get(i)))
                    .as("snapshot of " + accountIds.get(i) + " must be untouched")
                    .isEqualTo(snapshotsBefore.get(i));
        }
        assertI4(accountIds.toArray(String[]::new));
        assertI5(accountIds);
    }

    private List<Snapshot> snapshots(List<String> accountIds) throws SQLException {
        List<Snapshot> snapshots = new ArrayList<>();
        for (String accountId : accountIds) {
            snapshots.add(snapshot(accountId));
        }
        return snapshots;
    }

    @Nested
    @DisplayName("happy paths: post, transfer, read")
    class HappyPaths {

        @Test
        @DisplayName("multi-leg JPY journal: 201, Location, full response shape, I4/I5 hold")
        void multi_leg_jpy_journal_posts_and_reads_back() throws SQLException {
            String subject = subject();
            // JPY (exponent 0) on purpose: minor units are exponent-blind (TEST-STRATEGY §2.2).
            String a = createAccount(marker("jpy-a"), "JPY", "ASSET", false);
            String b = createAccount(marker("jpy-b"), "JPY", "EXPENSE", false);
            String c = createAccount(marker("jpy-c"), "JPY", "LIABILITY", true);
            MvcTestResult result = postJournal(subject, journal("jpy books",
                    leg(a, 500, "JPY"), leg(b, 200, "JPY"), leg(c, -700, "JPY")));

            assertThat(result).hasStatus(HttpStatus.CREATED);
            String body = body(result);
            String id = JsonPath.read(body, "$.id");
            assertThat(result.getResponse().getHeader("Location"))
                    .endsWith("/api/v1/journal-entries/" + id);
            assertThat(JsonPath.<String>read(body, "$.entryType")).isEqualTo("JOURNAL");
            assertThat(JsonPath.<String>read(body, "$.description")).isEqualTo("jpy books");
            assertThat(JsonPath.<Object>read(body, "$.reversalOf")).isNull();
            assertThat(JsonPath.<String>read(body, "$.createdBy")).isEqualTo(subject);
            assertThat(JsonPath.<String>read(body, "$.postedAt")).isNotBlank();
            // Legs keep submitted order (I11's exactness proof relies on it) and full shape.
            assertThat(JsonPath.<List<String>>read(body, "$.postings[*].accountId"))
                    .containsExactly(a, b, c);
            assertThat(JsonPath.<List<Integer>>read(body, "$.postings[*].amount.amount"))
                    .containsExactly(500, 200, -700);
            assertThat(JsonPath.<List<String>>read(body, "$.postings[*].amount.currency"))
                    .containsExactly("JPY", "JPY", "JPY");

            // GET returns the identical representation; HEAD is served with the same access.
            MvcTestResult read = mvc.get().uri("/api/v1/journal-entries/{id}", id)
                    .with(reader()).exchange();
            assertThat(read).hasStatusOk();
            assertThat(body(read)).isEqualTo(body);
            assertThat(mvc.head().uri("/api/v1/journal-entries/{id}", id).with(reader()))
                    .hasStatusOk();

            assertThat(snapshot(a)).isEqualTo(new Snapshot(500, 1));
            assertThat(snapshot(b)).isEqualTo(new Snapshot(200, 1));
            assertThat(snapshot(c)).isEqualTo(new Snapshot(-700, 1));
            assertI4(a, b, c);
            assertI5(List.of(a, b, c));
        }

        @Test
        @DisplayName("I1: a multi-currency entry is legal iff EACH currency nets to zero — one entry, two currencies")
        void balanced_multi_currency_entry_posts() throws SQLException {
            String subject = subject();
            String e1 = createAccount(marker("mc-eur1"), "EUR", "ASSET", false);
            String e2 = createAccount(marker("mc-eur2"), "EUR", "EQUITY", true);
            String j1 = createAccount(marker("mc-bhd1"), "BHD", "ASSET", false);
            String j2 = createAccount(marker("mc-bhd2"), "BHD", "INCOME", true);
            MvcTestResult result = postJournal(subject, journal(null,
                    leg(e1, 100, "EUR"), leg(e2, -100, "EUR"),
                    leg(j1, 31, "BHD"), leg(j2, -31, "BHD")));
            assertThat(result).hasStatus(HttpStatus.CREATED);
            assertThat(JsonPath.<Object>read(body(result), "$.description")).isNull();
            assertI4(e1, e2, j1, j2);
            assertI5(List.of(e1, e2, j1, j2));
        }

        @Test
        @DisplayName("transfer signs are PLAN §5 verbatim: source leg = +amount (debit), target leg = −amount (credit)")
        void transfer_posts_with_pinned_signs() throws SQLException {
            String subject = subject();
            String source = createAccount(marker("tr-src"), "EUR", "ASSET", false);
            String target = createAccount(marker("tr-tgt"), "EUR", "ASSET", true);
            MvcTestResult result = postTransfer(subject, transfer(source, target, 250, "EUR"));

            assertThat(result).hasStatus(HttpStatus.CREATED);
            String body = body(result);
            String id = JsonPath.read(body, "$.id");
            assertThat(result.getResponse().getHeader("Location"))
                    .endsWith("/api/v1/journal-entries/" + id);
            assertThat(JsonPath.<String>read(body, "$.entryType")).isEqualTo("TRANSFER");
            assertThat(JsonPath.<List<String>>read(body, "$.postings[*].accountId"))
                    .containsExactly(source, target);
            assertThat(JsonPath.<List<Integer>>read(body, "$.postings[*].amount.amount"))
                    .containsExactly(250, -250);

            // The signs at rest, not just in the representation.
            assertThat(snapshot(source)).isEqualTo(new Snapshot(250, 1));
            assertThat(snapshot(target)).isEqualTo(new Snapshot(-250, 1));
            assertI4(source, target);
            assertI5(List.of(source, target));
        }
    }

    @Nested
    @DisplayName("404 family: path-addressed misses (never the payload's 422)")
    class NotFound {

        @Test
        @DisplayName("GET unknown entry → 404; malformed uuid → 400")
        void get_unknown_and_malformed() {
            assertThat(mvc.get().uri("/api/v1/journal-entries/{id}", UUID.randomUUID())
                    .with(reader()))
                    .hasStatus(HttpStatus.NOT_FOUND)
                    .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
            assertThat(mvc.get().uri("/api/v1/journal-entries/not-a-uuid").with(reader()))
                    .hasStatus(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("reversal of an unknown entry → 404 — the id is a path segment, so the miss is the resource's")
        void reversal_of_unknown_entry_is_404() throws SQLException {
            String subject = subject();
            assertThat(mvc.post().uri("/api/v1/journal-entries/{id}/reversal", UUID.randomUUID())
                    .with(writer(subject))
                    .header("Idempotency-Key", UUID.randomUUID().toString()))
                    .hasStatus(HttpStatus.NOT_FOUND)
                    .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
            assertThat(entryCount(subject)).isZero();
        }
    }

    @Nested
    @DisplayName("422 family: every rejection reason, each writing NOTHING (ADR-0004)")
    class Rejections {

        @Test
        @DisplayName("I1: unbalanced entry → 422 unbalanced-entry with exact integer residuals")
        void unbalanced_entry_is_rejected() throws SQLException {
            String subject = subject();
            String a = createAccount(marker("unb-a"), "EUR", "ASSET", false);
            String b = createAccount(marker("unb-b"), "EUR", "ASSET", true);
            List<String> accounts = List.of(a, b);
            List<Snapshot> before = snapshots(accounts);

            MvcTestResult result = postJournal(subject, journal(null,
                    leg(a, 100, "EUR"), leg(b, -99, "EUR")));
            assertThat(result).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                    .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
            assertThat(JsonPath.<String>read(body(result), "$.type"))
                    .isEqualTo(PROBLEMS + "unbalanced-entry");
            // One minor unit off IS unbalanced — and the residual is machine-readable.
            assertThat(JsonPath.<Integer>read(body(result), "$.residuals.EUR")).isEqualTo(1);
            assertNothingWritten(subject, accounts, before);
        }

        @Test
        @DisplayName("I2: single-leg entry → 422 too-few-postings")
        void single_leg_entry_is_rejected() throws SQLException {
            String subject = subject();
            String a = createAccount(marker("few-a"), "EUR", "ASSET", false);
            List<Snapshot> before = snapshots(List.of(a));

            MvcTestResult result = postJournal(subject, journal(null, leg(a, 100, "EUR")));
            assertThat(result).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(JsonPath.<String>read(body(result), "$.type"))
                    .isEqualTo(PROBLEMS + "too-few-postings");
            assertNothingWritten(subject, List.of(a), before);
        }

        @Test
        @DisplayName("I2: zero-amount leg → 422 zero-amount-posting naming the account")
        void zero_amount_leg_is_rejected() throws SQLException {
            String subject = subject();
            String a = createAccount(marker("zero-a"), "EUR", "ASSET", false);
            String b = createAccount(marker("zero-b"), "EUR", "ASSET", true);
            List<Snapshot> before = snapshots(List.of(a, b));

            MvcTestResult result = postJournal(subject, journal(null,
                    leg(a, 100, "EUR"), leg(b, 0, "EUR"), leg(b, -100, "EUR")));
            assertThat(result).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(JsonPath.<String>read(body(result), "$.type"))
                    .isEqualTo(PROBLEMS + "zero-amount-posting");
            assertThat(JsonPath.<String>read(body(result), "$.accountId")).isEqualTo(b);
            assertNothingWritten(subject, List.of(a, b), before);
        }

        @Test
        @DisplayName("legs in a currency the accounts do not hold → 422 currency-mismatch")
        void currency_mismatch_is_rejected() throws SQLException {
            String subject = subject();
            String a = createAccount(marker("cur-a"), "EUR", "ASSET", false);
            String b = createAccount(marker("cur-b"), "EUR", "ASSET", true);
            List<Snapshot> before = snapshots(List.of(a, b));

            // Balanced in USD, so I1 passes and the account-currency rule is the one that fires
            // (it is decided under the lock, against the accounts' own currencies).
            MvcTestResult result = postJournal(subject, journal(null,
                    leg(a, 100, "USD"), leg(b, -100, "USD")));
            assertThat(result).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(JsonPath.<String>read(body(result), "$.type"))
                    .isEqualTo(PROBLEMS + "currency-mismatch");
            assertNothingWritten(subject, List.of(a, b), before);
        }

        @Test
        @DisplayName("ADR-0001: a running draft sum outside 64 bits → 422 amount-overflow, never a wrapped total")
        void draft_sum_overflow_is_rejected() throws SQLException {
            String subject = subject();
            String a = createAccount(marker("ovf-a"), "EUR", "ASSET", true);
            String b = createAccount(marker("ovf-b"), "EUR", "ASSET", true);
            List<Snapshot> before = snapshots(List.of(a, b));

            // MAX + 1 overflows while ACCUMULATING even though the four legs net to zero —
            // exactly the case a wrapping sum would wave through as "balanced".
            MvcTestResult result = postJournal(subject, journal(null,
                    leg(a, Long.MAX_VALUE, "EUR"), leg(a, 1, "EUR"),
                    leg(b, -Long.MAX_VALUE, "EUR"), leg(b, -1, "EUR")));
            assertThat(result).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(JsonPath.<String>read(body(result), "$.type"))
                    .isEqualTo(PROBLEMS + "amount-overflow");
            assertNothingWritten(subject, List.of(a, b), before);
        }

        @Test
        @DisplayName("ADR-0001: a new balance outside 64 bits → 422 amount-overflow; the committed state stays put")
        void balance_overflow_is_rejected() throws SQLException {
            String subject = subject();
            String a = createAccount(marker("bal-ovf-a"), "EUR", "ASSET", false);
            String b = createAccount(marker("bal-ovf-b"), "EUR", "ASSET", true);
            assertThat(postJournal(subject, journal(null,
                    leg(a, Long.MAX_VALUE, "EUR"), leg(b, -Long.MAX_VALUE, "EUR"))))
                    .hasStatus(HttpStatus.CREATED);
            List<Snapshot> after = snapshots(List.of(a, b));

            // The draft is fine (+1/−1) — it is a's WOULD-BE balance MAX+1 that has no
            // representation; ADR-0002's single UPDATE needs a representable result.
            MvcTestResult result = postJournal(subject, journal(null,
                    leg(a, 1, "EUR"), leg(b, -1, "EUR")));
            assertThat(result).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(JsonPath.<String>read(body(result), "$.type"))
                    .isEqualTo(PROBLEMS + "amount-overflow");
            assertThat(entryCount(subject)).as("only the first entry exists").isEqualTo(1);
            assertThat(snapshots(List.of(a, b))).isEqualTo(after);
            assertI4(a, b);
            assertI5(List.of(a, b));
        }

        @Test
        @DisplayName("I6: transfer overdrawing a strict account → 422 overdraft naming the account")
        void overdraft_is_rejected() throws SQLException {
            String subject = subject();
            String source = createAccount(marker("ovd-src"), "EUR", "ASSET", false);
            String target = createAccount(marker("ovd-tgt"), "EUR", "ASSET", false);
            List<Snapshot> before = snapshots(List.of(source, target));

            // The CREDIT side of the transfer (−100) would take the strict ASSET target to
            // natural −100 — I6's floor is on the natural balance, under the lock.
            MvcTestResult result = postTransfer(subject, transfer(source, target, 100, "EUR"));
            assertThat(result).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(JsonPath.<String>read(body(result), "$.type"))
                    .isEqualTo(PROBLEMS + "overdraft");
            assertThat(JsonPath.<String>read(body(result), "$.accountId")).isEqualTo(target);
            assertNothingWritten(subject, List.of(source, target), before);
        }

        @Test
        @DisplayName("I12: posting to a FROZEN account → 422 account-frozen")
        void posting_to_frozen_account_is_rejected() throws SQLException {
            String subject = subject();
            String a = createAccount(marker("frz-a"), "EUR", "ASSET", false);
            String b = createAccount(marker("frz-b"), "EUR", "ASSET", true);
            patchStatus(b, "FROZEN");
            List<Snapshot> before = snapshots(List.of(a, b));

            MvcTestResult result = postJournal(subject, journal(null,
                    leg(a, 50, "EUR"), leg(b, -50, "EUR")));
            assertThat(result).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(JsonPath.<String>read(body(result), "$.type"))
                    .isEqualTo(PROBLEMS + "account-frozen");
            assertNothingWritten(subject, List.of(a, b), before);
        }

        @Test
        @DisplayName("I12: posting to a CLOSED account → 422 account-closed (the M1 slug, as promised)")
        void posting_to_closed_account_is_rejected() throws SQLException {
            String subject = subject();
            String a = createAccount(marker("cls-a"), "EUR", "ASSET", false);
            String b = createAccount(marker("cls-b"), "EUR", "ASSET", true);
            patchStatus(b, "CLOSED");
            List<Snapshot> before = snapshots(List.of(a, b));

            MvcTestResult result = postJournal(subject, journal(null,
                    leg(a, 50, "EUR"), leg(b, -50, "EUR")));
            assertThat(result).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(JsonPath.<String>read(body(result), "$.type"))
                    .isEqualTo(PROBLEMS + "account-closed");
            assertNothingWritten(subject, List.of(a, b), before);
        }

        @Test
        @DisplayName("unknown accounts in the payload → 422 unknown-account, all offenders listed — the promised not-404")
        void unknown_payload_accounts_are_422_never_404() throws SQLException {
            String subject = subject();
            String known = createAccount(marker("unk-known"), "EUR", "ASSET", true);
            String ghost1 = UUID.randomUUID().toString();
            String ghost2 = UUID.randomUUID().toString();
            List<Snapshot> before = snapshots(List.of(known));

            MvcTestResult result = postJournal(subject, journal(null,
                    leg(known, 100, "EUR"), leg(ghost1, -60, "EUR"), leg(ghost2, -40, "EUR")));
            // The M1 forward-contract, discharged: a payload-referenced unknown account is a
            // 422 with its own type URI — 404 stays reserved for path-addressed lookups.
            assertThat(result).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(JsonPath.<String>read(body(result), "$.type"))
                    .isEqualTo(PROBLEMS + "unknown-account");
            assertThat(JsonPath.<List<String>>read(body(result), "$.accountIds"))
                    .containsExactlyElementsOf(Stream.of(ghost1, ghost2).sorted().toList());
            assertNothingWritten(subject, List.of(known), before);
        }
    }

    @Nested
    @DisplayName("400 family: shape defects the DTO layer refuses before the domain")
    class Shape {

        @Test
        @DisplayName("invalid currency, absent postings, non-positive transfer amount → 400 problem")
        void malformed_requests_are_400() {
            String subject = subject();
            String a = createAccount(marker("shape-a"), "EUR", "ASSET", false);
            String b = createAccount(marker("shape-b"), "EUR", "ASSET", true);
            for (String json : List.of(
                    journal(null, leg(a, 100, "ZZZ"), leg(b, -100, "ZZZ")),
                    journal(null, leg(a, 100, "eur"), leg(b, -100, "eur")),
                    "{\"description\": null, \"postings\": []}",
                    "{\"description\": null}",
                    "not json")) {
                assertThat(postJournal(subject, json)).as(json)
                        .hasStatus(HttpStatus.BAD_REQUEST)
                        .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
            }
            // PLAN §5 pins the transfer roles by sign — zero and negative amounts are shape
            // errors (a negative amount would merely express role confusion).
            for (long amount : List.of(0L, -100L)) {
                assertThat(postTransfer(subject, transfer(a, b, amount, "EUR")))
                        .as("transfer amount " + amount)
                        .hasStatus(HttpStatus.BAD_REQUEST);
            }
        }

        @Test
        @DisplayName("ADR-0001: fractional, integral-decimal, scientific, and quoted-string amounts all fail binding → 400, nothing written")
        void non_integer_json_amounts_are_400() throws SQLException {
            String subject = subject();
            String a = createAccount(marker("strict-a"), "EUR", "ASSET", false);
            String b = createAccount(marker("strict-b"), "EUR", "ASSET", true);
            List<Snapshot> before = snapshots(List.of(a, b));

            // ADR-0001's wire contract: amounts are JSON integers in minor units, and a
            // fractional amount is a problem response, never a truncation. Jackson's lenient
            // defaults would bind 10.99 as 10 and coerce "250" to 250 — silent money-mangling;
            // JacksonConfig pins Float-shape and String-shape into integer targets to Fail.
            // 1e2 IS integer-valued but arrives as a JSON float token: rejected with the rest,
            // deliberately — the wire contract says integer literal, not integer value.
            for (String amount : List.of("10.99", "100.0", "1e2", "\"250\"")) {
                String offendingLeg =
                        "{\"accountId\": \"%s\", \"amount\": {\"amount\": %s, \"currency\": \"EUR\"}}"
                                .formatted(a, amount);
                assertThat(postJournal(subject, journal(null, offendingLeg, leg(b, -100, "EUR"))))
                        .as("journal amount " + amount)
                        .hasStatus(HttpStatus.BAD_REQUEST)
                        .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
                String transferJson = """
                        {"sourceAccountId": "%s", "targetAccountId": "%s",
                         "amount": {"amount": %s, "currency": "EUR"}}
                        """.formatted(a, b, amount);
                assertThat(postTransfer(subject, transferJson))
                        .as("transfer amount " + amount)
                        .hasStatus(HttpStatus.BAD_REQUEST)
                        .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
            }
            assertNothingWritten(subject, List.of(a, b), before);
        }
    }

    @Nested
    @DisplayName("401 family: a token without a subject cannot attribute created_by")
    class SubjectlessToken {

        @Test
        @DisplayName("PLAN §7: a sub-less JWT is 401 on all three write endpoints — defective credential, nothing written")
        void subjectless_token_is_401_on_every_write_endpoint() throws SQLException {
            String a = createAccount(marker("nosub-a"), "EUR", "ASSET", false);
            String b = createAccount(marker("nosub-b"), "EUR", "ASSET", true);
            List<Snapshot> before = snapshots(List.of(a, b));

            // 401, not 400: the request body is fine — the CREDENTIAL cannot name a creator
            // (the doctrine split the class javadoc pins). The reversal uses a random path id:
            // the guard must fire before the 404 lookup, or a sub-less token could enumerate
            // which entry ids exist.
            List<MvcTestResult> results = List.of(
                    mvc.post().uri("/api/v1/journal-entries").with(subjectlessWriter())
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(journal(null, leg(a, 100, "EUR"), leg(b, -100, "EUR")))
                            .exchange(),
                    mvc.post().uri("/api/v1/transfers").with(subjectlessWriter())
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(transfer(a, b, 100, "EUR"))
                            .exchange(),
                    mvc.post().uri("/api/v1/journal-entries/{id}/reversal", UUID.randomUUID())
                            .with(subjectlessWriter())
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .exchange());
            for (MvcTestResult result : results) {
                assertThat(result)
                        .as(result.getRequest().getRequestURI())
                        .hasStatus(HttpStatus.UNAUTHORIZED)
                        .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
                assertThat(result.getResponse().getHeader("WWW-Authenticate"))
                        .as("RFC 6750: every 401 names the challenge scheme")
                        .startsWith("Bearer");
            }
            assertThat(postingCount(List.of(a, b))).as("rejected credential wrote postings").isZero();
            assertThat(snapshots(List.of(a, b))).isEqualTo(before);
        }
    }

    @Nested
    @DisplayName("PLAN §8: posting metrics through the config decorator")
    class Metrics {

        private long timerCount(String entryType, String outcome) {
            Timer timer = meterRegistry.find("ledger.posting.duration")
                    .tags("entry_type", entryType, "outcome", outcome).timer();
            return timer == null ? 0 : timer.count();
        }

        private double rejectedCount(String reason) {
            Counter counter = meterRegistry.find("ledger.posting.rejected")
                    .tags("reason", reason).counter();
            return counter == null ? 0 : counter.count();
        }

        @Test
        @DisplayName("duration counts posted and rejected outcomes; rejected carries the problem slug as its reason")
        void posting_metrics_are_recorded() {
            String subject = subject();
            String source = createAccount(marker("met-src"), "EUR", "ASSET", false);
            String lenient = createAccount(marker("met-lenient"), "EUR", "ASSET", true);
            String strict = createAccount(marker("met-strict"), "EUR", "ASSET", false);
            long postedBefore = timerCount("TRANSFER", "posted");
            long rejectedBefore = timerCount("TRANSFER", "rejected");
            double overdraftBefore = rejectedCount("overdraft");

            assertThat(postTransfer(subject, transfer(source, lenient, 40, "EUR")))
                    .hasStatus(HttpStatus.CREATED);
            assertThat(timerCount("TRANSFER", "posted")).isEqualTo(postedBefore + 1);

            assertThat(postTransfer(subject, transfer(source, strict, 40, "EUR")))
                    .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(timerCount("TRANSFER", "rejected")).isEqualTo(rejectedBefore + 1);
            // One vocabulary: the reason tag IS the problem slug (ProblemTypes javadoc).
            assertThat(rejectedCount("overdraft")).isEqualTo(overdraftBefore + 1);
        }
    }
}
