package io.github.essandhu.ledger.adapter.reconciliation;

import java.util.UUID;

import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;

import io.github.essandhu.ledger.application.port.out.BalanceComparison;
import io.github.essandhu.ledger.application.port.out.IdGenerator;
import io.github.essandhu.ledger.application.port.out.ReconciliationFinding;
import io.github.essandhu.ledger.application.port.out.ReconciliationRepository;

/**
 * The sweep's writer: stamps each drifted comparison (the processor filtered the clean ones)
 * with a fresh UUIDv7 and this run's id, and appends the findings through the port — inside
 * the step's chunk transaction, so a chunk's findings land atomically. {@code @StepScope} in
 * the config because the run id is a job parameter: late-bound per execution, and two
 * concurrent sweeps must not share one writer's idea of "the run".
 */
class FindingsWriter implements ItemWriter<BalanceComparison> {

    private final ReconciliationRepository reconciliation;
    private final IdGenerator ids;
    private final UUID runId;

    FindingsWriter(ReconciliationRepository reconciliation, IdGenerator ids, UUID runId) {
        this.reconciliation = reconciliation;
        this.ids = ids;
        this.runId = runId;
    }

    @Override
    public void write(Chunk<? extends BalanceComparison> drifted) {
        reconciliation.insertFindings(drifted.getItems().stream()
                .map(comparison -> ReconciliationFinding.of(ids.nextId(), runId,
                        (BalanceComparison) comparison))
                .toList());
    }
}
