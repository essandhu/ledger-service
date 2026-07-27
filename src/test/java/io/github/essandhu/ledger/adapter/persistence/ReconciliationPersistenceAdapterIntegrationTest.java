package io.github.essandhu.ledger.adapter.persistence;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.essandhu.ledger.application.port.out.AccountRepository;
import io.github.essandhu.ledger.application.port.out.BalanceComparison;
import io.github.essandhu.ledger.application.port.out.BalanceRepository;
import io.github.essandhu.ledger.application.port.out.ReconciliationRepository;
import io.github.essandhu.ledger.application.port.out.ReconciliationRun;
import io.github.essandhu.ledger.domain.model.Account;
import io.github.essandhu.ledger.domain.model.AccountId;
import io.github.essandhu.ledger.domain.model.AccountType;
import io.github.essandhu.ledger.domain.model.CurrencyCode;
import io.github.essandhu.ledger.support.LedgerIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The reconciliation port's database half at the adapter boundary (I15): the run row's
 * terminal-transition guards and the keyset comparison scan. The happy finish path — trigger
 * to verdict over HTTP — is {@code ReconciliationJobIntegrationTest}'s subject; this class pins
 * what only the adapter can promise: the RUNNING guard turns any second (or unopened) finish
 * into a LOUD zero-row failure, a failed run's result columns stay NULL and read back as EMPTY
 * results, and the strictly-greater keyset resume can neither skip nor repeat an account.
 *
 * <p>Shared-context discipline: run rows are additive-safe audit history under fresh random
 * ids; the scan fixtures are dedicated zero-seeded accounts (account + zero snapshot in one
 * transaction, the V2 forward-contract), so no posting or balance data is ever touched and
 * every fixture is at rest with snapshot = Σ postings = 0.
 */
@LedgerIntegrationTest
@DisplayName("M6 adapter guards: run terminal transitions and the keyset comparison scan")
class ReconciliationPersistenceAdapterIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-07-25T09:00:00Z");
    private static final Instant T1 = T0.plusSeconds(90);

    @Autowired
    private ReconciliationRepository reconciliation;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private BalanceRepository balances;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Test
    @DisplayName("failRun stamps FAILED + finishedAt onto the RUNNING row and leaves the result columns NULL — read back as EMPTY results")
    void fail_run_stamps_failed_and_leaves_results_empty() {
        UUID runId = UUID.randomUUID();
        String subject = "recon-adapter-" + UUID.randomUUID();
        transactionTemplate.executeWithoutResult(tx ->
                reconciliation.insertRun(runId, T0, subject));

        // Pre-terminal read-back: the open row's NULL result columns must translate to an
        // EMPTY results, never a zeroed Results — V5's results_shape pact (counts exist
        // exactly when there is a verdict), re-run by the record constructor on every load.
        ReconciliationRun open = findRun(runId);
        assertThat(open.status()).isEqualTo(ReconciliationRun.Status.RUNNING);
        assertThat(open.startedAt()).isEqualTo(T0);
        assertThat(open.triggeredBy()).isEqualTo(subject);
        assertThat(open.finishedAt()).isEmpty();
        assertThat(open.results()).isEmpty();

        transactionTemplate.executeWithoutResult(tx -> reconciliation.failRun(runId, T1));

        ReconciliationRun failed = findRun(runId);
        assertThat(failed.status()).isEqualTo(ReconciliationRun.Status.FAILED);
        assertThat(failed.finishedAt()).contains(T1);
        assertThat(failed.results())
                .as("partial counts from an aborted sweep would be a lie — columns stay NULL")
                .isEmpty();
    }

    @Test
    @DisplayName("the RUNNING guard makes failRun single-shot: a second fail is a zero-row update surfaced loudly")
    void fail_run_of_an_already_terminal_run_fails_loudly() {
        UUID runId = UUID.randomUUID();
        transactionTemplate.executeWithoutResult(tx ->
                reconciliation.insertRun(runId, T0, "recon-adapter-" + UUID.randomUUID()));
        transactionTemplate.executeWithoutResult(tx -> reconciliation.failRun(runId, T1));

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(
                tx -> reconciliation.failRun(runId, T1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("touched 0");
    }

    @Test
    @DisplayName("failRun of a run that was never opened fails loudly instead of recording nothing")
    void fail_run_of_a_missing_run_fails_loudly() {
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(
                tx -> reconciliation.failRun(UUID.randomUUID(), T1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("touched 0");
    }

    @Test
    @DisplayName("finishRun of a run that was never opened fails loudly instead of recording nothing")
    void finish_run_of_a_missing_run_fails_loudly() {
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(
                tx -> reconciliation.finishRun(UUID.randomUUID(), T1,
                        ReconciliationRun.Status.CLEAN,
                        new ReconciliationRun.Results(0, 0, 0, 0, 0))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("touched 0");
    }

    @Test
    @DisplayName("comparePage resumes strictly after the watermark in the database's bytewise uuid order — no row at or before it returns")
    void compare_page_resumes_strictly_after_the_watermark() {
        // Three dedicated scan rows guarantee the resumed page is nonempty whatever the shared
        // schema holds: at least the third-smallest id lies beyond a two-row first page.
        zeroBalanceAccounts(3);

        List<BalanceComparison> first =
                transactionTemplate.execute(tx -> reconciliation.comparePage(null, 2));
        assertThat(first).hasSize(2);
        assertThat(idsOf(first)).isSortedAccordingTo(BalanceRepository.UUID_BYTEWISE_ORDER);

        // Immediate-successor pin, no global knowledge needed: the first page holds the two
        // globally smallest ids, so resuming after row 0 must yield row 1 as the very first
        // element — an OFFSET-style or late-starting resume fails here deterministically.
        UUID smallest = first.getFirst().accountId().value();
        List<BalanceComparison> afterSmallest =
                transactionTemplate.execute(tx -> reconciliation.comparePage(smallest, 2));
        assertThat(afterSmallest.getFirst().accountId())
                .isEqualTo(first.get(1).accountId());

        UUID watermark = first.getLast().accountId().value();
        List<BalanceComparison> second =
                transactionTemplate.execute(tx -> reconciliation.comparePage(watermark, 2));

        assertThat(second).isNotEmpty();
        assertThat(idsOf(second))
                .as("strictly-greater resume: the watermark row itself must not repeat")
                .allSatisfy(id -> assertThat(
                        BalanceRepository.UUID_BYTEWISE_ORDER.compare(id, watermark))
                        .isPositive());
        assertThat(idsOf(second)).doesNotContainAnyElementsOf(idsOf(first));
        assertThat(idsOf(second)).isSortedAccordingTo(BalanceRepository.UUID_BYTEWISE_ORDER);
    }

    @Test
    @DisplayName("the keyset walk reaches every account exactly once — fresh zero-seeded accounts appear with equal (0, 0) pairs")
    void keyset_walk_never_skips_and_never_repeats() {
        List<AccountId> dedicated = zeroBalanceAccounts(3);

        // Page size 2 guarantees the resume path executes (this class alone seeds more rows
        // than one page); the independent COUNT makes "never skips" a completeness proof over
        // the WHOLE table, not just this class's fixtures — tests run sequentially, so the
        // count is stable across the walk's per-page transactions.
        Map<UUID, BalanceComparison> visited = walkEntireScan(2);
        Integer totalRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM account_balance", Integer.class);
        assertThat(visited).hasSize(totalRows);

        for (AccountId id : dedicated) {
            BalanceComparison row = visited.get(id.value());
            assertThat(row)
                    .as("account %s must be reached by the scan — keyset never skips", id.value())
                    .isNotNull();
            // Zero seeds with no postings: both halves of the pair are (0, 0) and agree —
            // pins the row mapping for the degenerate account every scan must handle.
            assertThat(row.snapshotBalance()).isZero();
            assertThat(row.snapshotCount()).isZero();
            assertThat(row.computedBalance()).isZero();
            assertThat(row.computedCount()).isZero();
            assertThat(row.drifted()).isFalse();
        }
    }

    /**
     * Port-level fixture (the {@link BalanceLockIntegrationTest} idiom): account + zero
     * snapshot row in one transaction — the V2 forward-contract every below-use-case fixture
     * honors, and exactly the state the comparison scan must read as a clean (0, 0) pair.
     */
    private List<AccountId> zeroBalanceAccounts(int count) {
        return IntStream.range(0, count).mapToObj(i -> {
            AccountId id = new AccountId(UUID.randomUUID());
            Account account = Account.open(id, "recon-scan-probe-" + id.value(),
                    new CurrencyCode("EUR"), AccountType.ASSET, false, T0);
            transactionTemplate.executeWithoutResult(tx -> {
                accounts.insert(account);
                balances.insertZero(id, T0);
            });
            return id;
        }).toList();
    }

    private ReconciliationRun findRun(UUID runId) {
        return transactionTemplate.execute(tx -> reconciliation.findRun(runId)).orElseThrow();
    }

    private static List<UUID> idsOf(List<BalanceComparison> rows) {
        return rows.stream().map(row -> row.accountId().value()).toList();
    }

    /** Walks the scan to its short final page, failing on any revisit; the hard page cap turns
     * a resume that loops (instead of advancing) into a test failure rather than a hang. */
    private Map<UUID, BalanceComparison> walkEntireScan(int pageSize) {
        Map<UUID, BalanceComparison> visited = new LinkedHashMap<>();
        UUID watermark = null;
        for (int pages = 0; pages < 10_000; pages++) {
            UUID after = watermark;
            List<BalanceComparison> page = transactionTemplate.execute(
                    tx -> reconciliation.comparePage(after, pageSize));
            for (BalanceComparison row : page) {
                assertThat(visited.put(row.accountId().value(), row))
                        .as("keyset resume must never revisit an account")
                        .isNull();
            }
            if (page.size() < pageSize) {
                return visited;
            }
            watermark = page.getLast().accountId().value();
        }
        throw new IllegalStateException(
                "comparison scan did not terminate within 10000 pages — keyset resume is broken");
    }
}
