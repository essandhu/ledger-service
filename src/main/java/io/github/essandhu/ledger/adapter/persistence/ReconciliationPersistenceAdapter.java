package io.github.essandhu.ledger.adapter.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.stereotype.Component;

import io.github.essandhu.ledger.application.port.in.PageSpec;
import io.github.essandhu.ledger.application.port.in.ReconciliationFindingsPage;
import io.github.essandhu.ledger.application.port.in.ReconciliationRunPage;
import io.github.essandhu.ledger.application.port.out.BalanceComparison;
import io.github.essandhu.ledger.application.port.out.ReconciliationFinding;
import io.github.essandhu.ledger.application.port.out.ReconciliationRepository;
import io.github.essandhu.ledger.application.port.out.ReconciliationRun;
import io.github.essandhu.ledger.domain.model.AccountId;

/**
 * JPA implementation of the {@link ReconciliationRepository} port. Transactions are owned by
 * the callers — the Batch step's chunk transactions for the scan and the findings, the
 * application service's for the run record — so every method here joins whatever transaction
 * surrounds it (the house adapter rule). Nothing here locks: the comparison statements' own
 * READ COMMITTED snapshots are the whole consistency story (ADR-0002), and ADR-0003's single
 * lock site stays {@code BalancePersistenceAdapter.lockBalances}.
 */
@Component
class ReconciliationPersistenceAdapter implements ReconciliationRepository {

    private final ReconciliationRunJpaRepository runs;
    private final ReconciliationFindingJpaRepository findings;

    ReconciliationPersistenceAdapter(ReconciliationRunJpaRepository runs,
            ReconciliationFindingJpaRepository findings) {
        this.runs = runs;
        this.findings = findings;
    }

    @Override
    public List<BalanceComparison> comparePage(UUID afterAccountId, int pageSize) {
        List<ReconciliationRunJpaRepository.ComparisonRow> rows = afterAccountId == null
                ? runs.compareFirstPage(pageSize)
                : runs.comparePageAfter(afterAccountId, pageSize);
        return rows.stream()
                .map(row -> new BalanceComparison(new AccountId(row.getAccountId()),
                        row.getSnapshotBalance(), row.getSnapshotCount(),
                        row.getComputedBalance(), row.getComputedCount()))
                .toList();
    }

    @Override
    public void insertRun(UUID runId, Instant startedAt, String triggeredBy) {
        runs.save(ReconciliationRunJpaEntity.running(runId, startedAt, triggeredBy));
    }

    @Override
    public void finishRun(UUID runId, Instant finishedAt, ReconciliationRun.Status verdict,
            ReconciliationRun.Results results) {
        int updated = runs.finishRun(runId, finishedAt, verdict.name(),
                results.accountsChecked(), results.driftCount(),
                results.currencyMismatchCount(), results.postedAtMismatchCount(),
                results.unbalancedCurrencyCount());
        if (updated != 1) {
            // The RUNNING guard makes a double finish (or a finish of a run that was never
            // opened) a zero-row update — silently recording nothing would leave a run row
            // contradicting the sweep that just happened; fail loudly instead.
            throw new IllegalStateException(
                    "finishRun touched %d reconciliation_run rows for %s — expected exactly 1"
                            .formatted(updated, runId));
        }
    }

    @Override
    public void failRun(UUID runId, Instant finishedAt) {
        int updated = runs.failRun(runId, finishedAt);
        if (updated != 1) {
            throw new IllegalStateException(
                    "failRun touched %d reconciliation_run rows for %s — expected exactly 1"
                            .formatted(updated, runId));
        }
    }

    @Override
    public Optional<ReconciliationRun> findRun(UUID runId) {
        return runs.findById(runId).map(ReconciliationRunJpaEntity::toDomain);
    }

    @Override
    public void insertFindings(List<ReconciliationFinding> toInsert) {
        findings.saveAll(toInsert.stream()
                .map(ReconciliationFindingJpaEntity::fromDomain)
                .toList());
    }

    @Override
    public FindingAggregate aggregateFindings(UUID runId) {
        ReconciliationRunJpaRepository.FindingAggregateRow row = runs.aggregateFindings(runId);
        return new FindingAggregate(row.getDriftCount(), row.getAbsoluteDrift());
    }

    @Override
    public IntegrityCounts integrityCounts() {
        return new IntegrityCounts(runs.countCurrencyMismatches(),
                runs.countPostedAtMismatches(), runs.countUnbalancedCurrencies());
    }

    @Override
    public ReconciliationRunPage runs(PageSpec page) {
        // Descending id (M8c): the run history reads from its newest end, and a backwards scan
        // of the primary key needs no sort and no index V5 does not already have.
        Page<ReconciliationRunJpaEntity> result =
                runs.findAll(PageRequest.of(page.page(), page.size(), Sort.by(Direction.DESC, "id")));
        return new ReconciliationRunPage(
                result.getContent().stream().map(ReconciliationRunJpaEntity::toDomain).toList(),
                page.page(), page.size(), result.getTotalElements());
    }

    @Override
    public ReconciliationFindingsPage findings(UUID runId, PageSpec page) {
        Page<ReconciliationFindingJpaEntity> result = findings.findByRunId(runId,
                PageRequest.of(page.page(), page.size(), Sort.by("id")));
        return new ReconciliationFindingsPage(
                result.getContent().stream().map(ReconciliationFindingJpaEntity::toDomain).toList(),
                page.page(), page.size(), result.getTotalElements());
    }
}
