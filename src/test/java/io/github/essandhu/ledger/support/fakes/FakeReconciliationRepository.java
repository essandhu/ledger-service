package io.github.essandhu.ledger.support.fakes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.github.essandhu.ledger.application.port.in.PageSpec;
import io.github.essandhu.ledger.application.port.in.ReconciliationFindingsPage;
import io.github.essandhu.ledger.application.port.out.BalanceComparison;
import io.github.essandhu.ledger.application.port.out.BalanceRepository;
import io.github.essandhu.ledger.application.port.out.ReconciliationFinding;
import io.github.essandhu.ledger.application.port.out.ReconciliationRepository;
import io.github.essandhu.ledger.application.port.out.ReconciliationRun;

/**
 * In-memory {@link ReconciliationRepository} that ENFORCES the port contracts the adapter gets
 * from the database: run ids are unique on insert, finish/fail touch exactly one RUNNING row
 * (the V5 RUNNING-guard), findings are unique per (run, account), and the comparison keyset
 * pages in the database's uuid order — derived from
 * {@link BalanceRepository#UUID_BYTEWISE_ORDER}, the ONE Java-side definition of that order.
 */
public class FakeReconciliationRepository implements ReconciliationRepository {

    private static final Comparator<BalanceComparison> SCAN_ORDER = Comparator
            .comparing(comparison -> comparison.accountId().value(),
                    BalanceRepository.UUID_BYTEWISE_ORDER);

    private final Map<UUID, ReconciliationRun> runs = new LinkedHashMap<>();
    private final List<ReconciliationFinding> findings = new ArrayList<>();
    private List<BalanceComparison> comparisons = List.of();
    private IntegrityCounts integrityCounts = new IntegrityCounts(0, 0, 0);

    // ── seeding / inspection for tests ───────────────────────────────────────────────────

    public void seedComparisons(List<BalanceComparison> all) {
        comparisons = List.copyOf(all);
    }

    public void seedIntegrityCounts(IntegrityCounts counts) {
        integrityCounts = counts;
    }

    public ReconciliationRun runRecord(UUID runId) {
        ReconciliationRun run = runs.get(runId);
        if (run == null) {
            throw new AssertionError("no run " + runId + " recorded");
        }
        return run;
    }

    public List<ReconciliationFinding> findingsOf(UUID runId) {
        return findings.stream().filter(finding -> finding.runId().equals(runId)).toList();
    }

    // ── the port ─────────────────────────────────────────────────────────────────────────

    @Override
    public List<BalanceComparison> comparePage(UUID afterAccountId, int pageSize) {
        return comparisons.stream()
                .sorted(SCAN_ORDER)
                .filter(comparison -> afterAccountId == null
                        || BalanceRepository.UUID_BYTEWISE_ORDER
                                .compare(comparison.accountId().value(), afterAccountId) > 0)
                .limit(pageSize)
                .toList();
    }

    @Override
    public void insertRun(UUID runId, Instant startedAt, String triggeredBy) {
        if (runs.containsKey(runId)) {
            throw new IllegalStateException("duplicate run id " + runId + " (PK contract)");
        }
        runs.put(runId, new ReconciliationRun(runId, startedAt, Optional.empty(),
                ReconciliationRun.Status.RUNNING, triggeredBy, Optional.empty()));
    }

    @Override
    public void finishRun(UUID runId, Instant finishedAt, ReconciliationRun.Status verdict,
            ReconciliationRun.Results results) {
        ReconciliationRun running = requireRunning(runId, "finishRun");
        runs.put(runId, new ReconciliationRun(runId, running.startedAt(),
                Optional.of(finishedAt), verdict, running.triggeredBy(), Optional.of(results)));
    }

    @Override
    public void failRun(UUID runId, Instant finishedAt) {
        ReconciliationRun running = requireRunning(runId, "failRun");
        runs.put(runId, new ReconciliationRun(runId, running.startedAt(),
                Optional.of(finishedAt), ReconciliationRun.Status.FAILED,
                running.triggeredBy(), Optional.empty()));
    }

    private ReconciliationRun requireRunning(UUID runId, String operation) {
        ReconciliationRun run = runs.get(runId);
        if (run == null || run.status() != ReconciliationRun.Status.RUNNING) {
            throw new IllegalStateException(
                    operation + " touched 0 RUNNING rows for " + runId + " (adapter contract)");
        }
        return run;
    }

    @Override
    public Optional<ReconciliationRun> findRun(UUID runId) {
        return Optional.ofNullable(runs.get(runId));
    }

    @Override
    public void insertFindings(List<ReconciliationFinding> toInsert) {
        for (ReconciliationFinding finding : toInsert) {
            boolean duplicate = findings.stream().anyMatch(existing ->
                    existing.runId().equals(finding.runId())
                            && existing.accountId().equals(finding.accountId()));
            if (duplicate) {
                throw new IllegalStateException("duplicate finding for account "
                        + finding.accountId() + " in run " + finding.runId()
                        + " (V5 unique contract)");
            }
            findings.add(finding);
        }
    }

    @Override
    public FindingAggregate aggregateFindings(UUID runId) {
        List<ReconciliationFinding> ofRun = findingsOf(runId);
        long absolute = ofRun.stream().mapToLong(finding -> Math.abs(finding.delta())).sum();
        return new FindingAggregate(ofRun.size(), absolute);
    }

    @Override
    public IntegrityCounts integrityCounts() {
        return integrityCounts;
    }

    @Override
    public ReconciliationFindingsPage findings(UUID runId, PageSpec page) {
        List<ReconciliationFinding> ofRun = findingsOf(runId).stream()
                .sorted(Comparator.comparing(ReconciliationFinding::id,
                        BalanceRepository.UUID_BYTEWISE_ORDER))
                .toList();
        List<ReconciliationFinding> content = ofRun.stream()
                .skip((long) page.page() * page.size())
                .limit(page.size())
                .toList();
        return new ReconciliationFindingsPage(content, page.page(), page.size(), ofRun.size());
    }
}
