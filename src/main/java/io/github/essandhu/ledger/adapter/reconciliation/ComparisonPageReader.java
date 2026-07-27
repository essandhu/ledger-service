package io.github.essandhu.ledger.adapter.reconciliation;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

import org.springframework.batch.infrastructure.item.ItemReader;

import io.github.essandhu.ledger.application.port.out.BalanceComparison;
import io.github.essandhu.ledger.application.port.out.ReconciliationRepository;

/**
 * The sweep's reader: pulls keyset pages of {@link BalanceComparison} through the port and
 * hands them to the step one at a time. Stateful by nature (page buffer + resume key), which
 * is why the config declares it {@code @StepScope} — every step execution gets a fresh
 * instance, so a second sweep starts from the top instead of inheriting an exhausted reader.
 * Hand-rolled rather than {@code JdbcPagingItemReader}: the comparison SQL is a load-bearing
 * query that belongs in the persistence adapter behind the port (the house rule), and this
 * job is deliberately not restartable ({@code ReconciliationJobConfig}) so the framework
 * reader's ExecutionContext bookkeeping would buy nothing.
 */
class ComparisonPageReader implements ItemReader<BalanceComparison> {

    private final ReconciliationRepository reconciliation;
    private final int pageSize;

    private final Deque<BalanceComparison> buffer = new ArrayDeque<>();
    private UUID afterAccountId;
    private boolean exhausted;

    ComparisonPageReader(ReconciliationRepository reconciliation, int pageSize) {
        this.reconciliation = reconciliation;
        this.pageSize = pageSize;
    }

    @Override
    public BalanceComparison read() {
        if (buffer.isEmpty() && !exhausted) {
            List<BalanceComparison> page = reconciliation.comparePage(afterAccountId, pageSize);
            buffer.addAll(page);
            if (!page.isEmpty()) {
                afterAccountId = page.getLast().accountId().value();
            }
            // A short page means the scan is done; remembering that spares the tail a
            // guaranteed-empty query per remaining chunk boundary.
            exhausted = page.size() < pageSize;
        }
        return buffer.poll();
    }
}
