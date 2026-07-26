package io.github.essandhu.ledger.concurrency;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import io.github.essandhu.ledger.config.LedgerRealmRoleConverter;
import io.github.essandhu.ledger.support.LedgerConcurrencyTest;

import static java.util.Map.of;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/**
 * Shared machinery of the M5 stress suites (TEST-STRATEGY §4): every workload drives the REAL
 * HTTP surface — MockMvc requests through the full filter chain, each worker thread carrying
 * its own per-request JWT (the {@code jwt()} post-processor is request-scoped, so nothing
 * thread-local leaks between workers), so the proofs cover exactly the stack a production
 * client hits, including the web adapter's lost-race retry (ADR-0004's backstop ending, which
 * port-level tests would bypass). Assertions read the DATABASE through the app pool — success
 * is never inferred from client-side bookkeeping alone (the §4 honesty rule) — and every row
 * this class creates carries per-test random ids/subjects (additive-safe shared-schema
 * discipline, TEST-STRATEGY §2).
 *
 * <p>Worker-count guidance baked into the defaults: Hikari's pool is the Boot default of 10
 * connections and a posting holds its connection while waiting on row locks (ADR-0003's
 * accepted cost), so suites default to 8 workers — above that, workers measure the connection
 * pool queue, not the lock protocol. {@code -Dledger.concurrency.threads} can crank it anyway
 * (a nightly run measuring pool-pressure behavior is a legitimate experiment; the bounded
 * harness timeout still bounds it).
 */
@LedgerConcurrencyTest
abstract class ConcurrencyTestSupport {

    static final String PROBLEMS = "https://essandhu.github.io/ledger/problems/";
    static final String REPLAYED_HEADER = "Idempotency-Replayed";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    DataSource dataSource;

    @Autowired
    MeterRegistry meterRegistry;

    static RequestPostProcessor admin() {
        return jwt().jwt(j -> j.claim("realm_access", of("roles", List.of("LEDGER_ADMIN"))))
                .authorities(new LedgerRealmRoleConverter());
    }

    static RequestPostProcessor writer(String subject) {
        return jwt().jwt(j -> j.subject(subject)
                        .claim("realm_access", of("roles", List.of("LEDGER_WRITE"))))
                .authorities(new LedgerRealmRoleConverter());
    }

    static String subject(String label) {
        return label + "-" + UUID.randomUUID();
    }

    static String marker(String label) {
        return label + "-" + UUID.randomUUID();
    }

    static String body(MvcTestResult result) {
        try {
            return result.getResponse().getContentAsString();
        } catch (java.io.UnsupportedEncodingException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** The RFC 9457 {@code type} of a problem response — how workers classify expected 422s. */
    static String problemType(MvcTestResult result) {
        return JsonPath.read(body(result), "$.type");
    }

    /** EUR ASSET account via the API (debit-positive raw IS the natural balance — the sign
     * arithmetic in every workload stays first-grade on purpose). */
    String createAccount(String name, boolean allowNegative) {
        MvcTestResult result = mvc.post().uri("/api/v1/accounts").with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name": "%s", "currency": "EUR", "type": "ASSET", "allowNegative": %s}
                        """.formatted(name, allowNegative))
                .exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return JsonPath.read(body(result), "$.id");
    }

    static String transferJson(String source, String target, long amount) {
        return """
                {"sourceAccountId": "%s", "targetAccountId": "%s",
                 "amount": {"amount": %d, "currency": "EUR"}}
                """.formatted(source, target, amount);
    }

    static String journalJson(String debitAccount, String creditAccount, long amount) {
        return """
                {"description": null, "postings": [
                  {"accountId": "%s", "amount": {"amount": %d, "currency": "EUR"}},
                  {"accountId": "%s", "amount": {"amount": %d, "currency": "EUR"}}]}
                """.formatted(debitAccount, amount, creditAccount, -amount);
    }

    MvcTestResult postTransfer(String subject, String key, String json) {
        return mvc.post().uri("/api/v1/transfers").with(writer(subject))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(json).exchange();
    }

    MvcTestResult postJournal(String subject, String key, String json) {
        return mvc.post().uri("/api/v1/journal-entries").with(writer(subject))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(json).exchange();
    }

    // --- database truth (raw SQL through the app pool; scoped to rows this test created) ---

    record BalanceRow(long balance, long postingCount) {
    }

    BalanceRow balanceRow(String accountId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT balance, posting_count FROM account_balance WHERE account_id = ?")) {
            select.setObject(1, UUID.fromString(accountId));
            try (ResultSet row = select.executeQuery()) {
                assertThat(row.next()).as("balance row exists for " + accountId).isTrue();
                return new BalanceRow(row.getLong("balance"), row.getLong("posting_count"));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** I4's two halves in one statement: Σ(amount) and COUNT(*) over the account's postings —
     * what the snapshot row must equal after every committed transaction. */
    record PostingSum(long sum, long count) {
    }

    PostingSum postingSum(String accountId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT COALESCE(SUM(amount), 0) AS total, COUNT(*) AS legs"
                             + " FROM posting WHERE account_id = ?")) {
            select.setObject(1, UUID.fromString(accountId));
            try (ResultSet row = select.executeQuery()) {
                assertThat(row.next()).isTrue();
                return new PostingSum(row.getLong("total"), row.getLong("legs"));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Asserts I4 for one account: snapshot balance = Σ postings, posting_count = their count. */
    void assertSnapshotEqualsPostings(String accountId) {
        BalanceRow snapshot = balanceRow(accountId);
        PostingSum truth = postingSum(accountId);
        assertThat(snapshot.balance())
                .as("I4: snapshot balance equals SUM(posting.amount) for " + accountId)
                .isEqualTo(truth.sum());
        assertThat(snapshot.postingCount())
                .as("I4: posting_count watermark equals COUNT(posting) for " + accountId)
                .isEqualTo(truth.count());
    }

    long entryCount(String subject) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT count(*) FROM journal_entry WHERE created_by = ?")) {
            select.setString(1, subject);
            try (ResultSet row = select.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The account's raw posting amounts in ledger order — {@code (posted_at, id)}, the same
     * total order statements page by (I10's per-account order = commit order by construction),
     * so a prefix walk replays the account's history exactly as it committed. */
    List<Long> postingAmountsInLedgerOrder(String accountId) {
        List<Long> amounts = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT amount FROM posting WHERE account_id = ? ORDER BY posted_at, id")) {
            select.setObject(1, UUID.fromString(accountId));
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    amounts.add(row.getLong("amount"));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return amounts;
    }

    /** Sample count of the PLAN §8 lock-wait timer — shared registry, so callers assert
     * monotone growth (>=), never absolute values (additive-safe metric discipline). */
    long lockWaitCount() {
        Timer timer = meterRegistry.find("ledger.posting.lock.wait").timer();
        return timer == null ? 0 : timer.count();
    }

    /** Renders a worker's unexpected response into the failure the honesty rule demands. */
    static AssertionError unexpectedResponse(String what, MvcTestResult result) {
        return new AssertionError("unexpected response to %s: HTTP %d, body: %s"
                .formatted(what, result.getResponse().getStatus(), body(result)));
    }
}
