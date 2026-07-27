package io.github.essandhu.ledger.adapter.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.essandhu.ledger.application.port.out.AccountRepository;
import io.github.essandhu.ledger.application.port.out.BalanceRepository;
import io.github.essandhu.ledger.domain.model.Account;
import io.github.essandhu.ledger.domain.model.AccountBalance;
import io.github.essandhu.ledger.domain.model.AccountId;
import io.github.essandhu.ledger.domain.model.AccountType;
import io.github.essandhu.ledger.domain.model.CurrencyCode;
import io.github.essandhu.ledger.support.LedgerIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The half of the ADR-0003 lock protocol that needs a real database: the {@code SELECT … ORDER
 * BY account_id FOR UPDATE} query must impose the canonical bytewise UUID order on ARBITRARY
 * input — deadlock freedom hangs on the database's sort, not on caller discipline (the service
 * pre-sorts and {@code FakeBalanceRepository} polices that contract; this class proves the
 * adapter's defensive re-sort holds even for a caller that breaks it). This is the light M2
 * version of ADR-0003's M5 property-test preview: fixed ids spanning the signed/unsigned
 * disagreement instead of generated ones — including top bytes {@code 0x80} and {@code 0xf9},
 * which {@link UUID#compareTo} (signed longs) mis-sorts ahead of {@code 0x00}-first ids while
 * PostgreSQL's bytewise uuid order puts them last.
 *
 * <p>Also proves the other two port operations land in the DATABASE, checked by raw SQL so no
 * first-level cache can vouch for itself: {@code applyDelta} applies ADR-0002's literal
 * cumulative UPDATE exactly (I4's mechanism), and {@code insertZero} seeds the snapshot row
 * without which the lock protocol dies (the V2 forward-contract). Port + TransactionTemplate +
 * DataSource style mirrors {@link OptimisticLockIntegrationTest}; all rows carry per-test
 * random ids and marker names (additive-safe shared-schema discipline, TEST-STRATEGY §2).
 */
@LedgerIntegrationTest
@DisplayName("ADR-0003: canonical-order balance locking and the ADR-0002 snapshot bump")
class BalanceLockIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-07-22T10:00:00Z");
    private static final Instant T1 = T0.plusSeconds(60);
    private static final Instant T2 = T0.plusSeconds(120);

    @Autowired
    private BalanceRepository balances;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private TransactionTemplate transactionTemplate;

    /** The application pool — connects as the runtime role {@code ledger_app}. */
    @Autowired
    private DataSource dataSource;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    @DisplayName("ADR-0003: lockBalances(shuffled ids) returns rows in canonical bytewise order — including ids UUID.compareTo mis-sorts — and omits unknown ids")
    void lock_balances_returns_rows_in_canonical_order_regardless_of_input_order() {
        AccountId low = accountWithZeroBalance(0x0a);
        AccountId mid = accountWithZeroBalance(0x7f);
        AccountId high = accountWithZeroBalance(0x80); // negative as a signed long: compareTo sorts it FIRST
        AccountId top = accountWithZeroBalance(0xf9);
        // No row for this id: the port reads absence as "this account does not exist" —
        // additive-safe because no other test can have created a row under a random UUID.
        AccountId unknown = new AccountId(UUID.randomUUID());

        // Prove the fixture has teeth: an implementation sorting by UUID.compareTo (signed
        // longs) would order these differently, so it could not pass the assertion below.
        List<AccountId> canonical = List.of(low, mid, high, top);
        assertThat(canonical.stream()
                .sorted(Comparator.comparing(AccountId::value)).toList())
                .as("fixture spans the signed/unsigned disagreement — compareTo order must differ")
                .isNotEqualTo(canonical);

        // Deliberately disordered input. The port contract obliges callers to pre-sort, but the
        // adapter must not depend on that: its ORDER BY re-sorts unconditionally (defense in
        // depth, ADR-0003 — the port javadoc promises exactly this for arbitrary inputs).
        List<AccountId> shuffled = List.of(high, top, low, unknown, mid);
        List<AccountBalance> locked =
                transactionTemplate.execute(tx -> balances.lockBalances(shuffled));

        assertThat(locked).extracting(AccountBalance::accountId)
                .as("rows come back in the one total order every lock taker agrees on; the"
                        + " unknown id is simply absent")
                .containsExactly(low, mid, high, top);
    }

    @Test
    @DisplayName("PLAN §8: lock acquisition is timed — ledger.posting.lock.wait records a sample per lockBalances")
    void lock_wait_timer_records_each_acquisition() {
        AccountId id = accountWithZeroBalance(0x11);

        long before = lockWaitCount();
        transactionTemplate.executeWithoutResult(tx -> balances.lockBalances(List.of(id)));

        // Shared registry across the cached context: other tests may add samples, never remove
        // them — a monotone >= keeps this additive-safe.
        assertThat(lockWaitCount())
                .as("the FOR UPDATE query is wrapped in the lock-wait timer")
                .isGreaterThanOrEqualTo(before + 1);
    }

    @Test
    @DisplayName("I4: applyDelta lands ADR-0002's arithmetic exactly — balance += delta, posting_count += legs, updated_at = now, cumulatively")
    void apply_delta_bumps_balance_posting_count_and_updated_at_exactly() {
        AccountId id = accountWithZeroBalance(0x22);

        try {
            transactionTemplate.executeWithoutResult(tx -> {
                balances.lockBalances(List.of(id)); // the port contract: deltas apply only under the row's lock
                balances.applyDelta(id, 1234, 2, T1);
            });
            assertBalanceRow(id, 1234, 2, T1);

            transactionTemplate.executeWithoutResult(tx -> {
                balances.lockBalances(List.of(id));
                balances.applyDelta(id, -234, 3, T2);
            });
            // += semantics, not overwrite: the second bump accumulates onto the first.
            assertBalanceRow(id, 1000, 5, T2);
        } finally {
            // M6 discipline: these port-level bumps have no posting rows behind them, and the
            // reconciliation sweep now asserts snapshot = Σ postings AT REST for every account
            // — so the fixture compensates back to the zero seed (the JournalSchema restore
            // idiom), whatever prefix of the bumps got applied before a failure.
            transactionTemplate.executeWithoutResult(tx -> {
                List<AccountBalance> current = balances.lockBalances(List.of(id));
                if (!current.isEmpty()) {
                    AccountBalance row = current.get(0);
                    if (row.balance() != 0 || row.postingCount() != 0) {
                        balances.applyDelta(id, -row.balance(), -row.postingCount(), T2);
                    }
                }
            });
        }
    }

    @Test
    @DisplayName("ADR-0002: applyDelta against a missing snapshot row fails loudly — a zero-row bump would drop money silently")
    void apply_delta_on_missing_row_fails_loudly() {
        AccountId ghost = new AccountId(UUID.randomUUID());

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(
                tx -> balances.applyDelta(ghost, 1, 1, T1)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("V2 forward-contract: insertZero seeds the snapshot row the lock protocol requires — balance 0, count 0, updated_at = creation instant")
    void insert_zero_creates_the_zero_snapshot_row() {
        // The fixture inserts account + zero row in one transaction, exactly as the
        // create-account use case does; this assertion pins what insertZero wrote.
        AccountId id = accountWithZeroBalance(0x33);

        assertBalanceRow(id, 0, 0, T0);
    }

    @Test
    @DisplayName("ADR-0003 degenerate case: lockBalances([]) answers the empty lock set in Java — SQL is never consulted")
    void lock_balances_of_no_ids_returns_empty_without_sql() {
        // The pin has teeth because the alternative is loud: without the Java-side guard the
        // adapter would render a native IN () — a SQL syntax error — so an empty result
        // (rather than an exception out of the query) proves the degenerate answer was
        // produced before the database.
        List<AccountBalance> locked =
                transactionTemplate.execute(tx -> balances.lockBalances(List.of()));

        assertThat(locked)
                .as("no flow produces an empty lock set, but the answer must still be total")
                .isEmpty();
    }

    /**
     * Port-level fixture, below the use-case layer, so it honors by hand what the
     * create-account transaction honors in code: every account gets its zero snapshot row in
     * the same transaction (V2 forward-contract — the {@link OptimisticLockIntegrationTest}
     * fixture makes the same promise). {@code insertZero} here is not scaffolding: it is one
     * of the port operations under test.
     */
    private AccountId accountWithZeroBalance(int topByte) {
        AccountId id = idWithTopByte(topByte);
        Account account = Account.open(id, "balance-lock-probe-" + id.value(),
                new CurrencyCode("EUR"), AccountType.ASSET, false, T0);
        transactionTemplate.executeWithoutResult(tx -> {
            accounts.insert(account);
            balances.insertZero(id, T0);
        });
        return id;
    }

    /**
     * A random UUID whose most significant byte is forced to {@code topByte}: randomness in
     * the remaining 120 bits keeps rows additive-safe, the fixed top byte pins where the id
     * falls in the bytewise total order (and, for {@code >= 0x80}, makes the signed
     * {@code UUID.compareTo} order provably different).
     */
    private static AccountId idWithTopByte(int topByte) {
        UUID random = UUID.randomUUID();
        long msb = (random.getMostSignificantBits() & 0x00FF_FFFF_FFFF_FFFFL)
                | ((long) topByte << 56);
        return new AccountId(new UUID(msb, random.getLeastSignificantBits()));
    }

    private long lockWaitCount() {
        Timer timer = meterRegistry.find("ledger.posting.lock.wait").timer();
        return timer == null ? 0 : timer.count();
    }

    /** Raw SQL through the app pool — the snapshot must be right in the DATABASE, where
     * reconciliation (ADR-0002) will read it, not merely in a persistence context. */
    private void assertBalanceRow(AccountId id, long balance, long postingCount,
            Instant updatedAt) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement("""
                     SELECT balance, posting_count, updated_at
                     FROM account_balance
                     WHERE account_id = ?
                     """)) {
            select.setObject(1, id.value());
            try (ResultSet row = select.executeQuery()) {
                assertThat(row.next()).as("balance row exists for " + id.value()).isTrue();
                assertThat(row.getLong("balance")).isEqualTo(balance);
                assertThat(row.getLong("posting_count")).isEqualTo(postingCount);
                assertThat(row.getObject("updated_at", OffsetDateTime.class).toInstant())
                        .isEqualTo(updatedAt);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
