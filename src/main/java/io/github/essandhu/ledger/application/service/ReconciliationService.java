package io.github.essandhu.ledger.application.service;

import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

import io.github.essandhu.ledger.application.port.in.GetReconciliationRunQuery;
import io.github.essandhu.ledger.application.port.in.ListReconciliationFindingsQuery;
import io.github.essandhu.ledger.application.port.in.PageSpec;
import io.github.essandhu.ledger.application.port.in.ReconcileBalancesUseCase;
import io.github.essandhu.ledger.application.port.in.ReconciliationFindingsPage;
import io.github.essandhu.ledger.application.port.in.ReconciliationRunNotFound;
import io.github.essandhu.ledger.application.port.out.IdGenerator;
import io.github.essandhu.ledger.application.port.out.ReconciliationRepository;
import io.github.essandhu.ledger.application.port.out.ReconciliationRun;
import io.github.essandhu.ledger.application.port.out.ReconciliationTrigger;

/**
 * The caller-facing reconciliation use cases (PLAN §5, M6): trigger a sweep, read a run, list
 * its findings. The sweep itself — chunking, comparisons, findings, the run record — happens
 * behind {@link ReconciliationTrigger} (the Batch adapter) and
 * {@link ReconciliationRunService} (the job's record-keeper); this service is deliberately
 * thin so the core's reconciliation surface stays framework-free.
 */
public class ReconciliationService implements ReconcileBalancesUseCase,
        GetReconciliationRunQuery, ListReconciliationFindingsQuery {

    private final ReconciliationTrigger trigger;
    private final ReconciliationRepository reconciliation;
    private final IdGenerator ids;

    public ReconciliationService(ReconciliationTrigger trigger,
            ReconciliationRepository reconciliation, IdGenerator ids) {
        this.trigger = trigger;
        this.reconciliation = reconciliation;
        this.ids = ids;
    }

    /**
     * Deliberately NOT {@code @Transactional}: the trigger blocks for the whole synchronous
     * sweep, and the sweep's transactions are its own (chunk transactions in the step, record
     * transactions in {@link ReconciliationRunService}) — an outer transaction here would
     * pin one connection for the duration and could not even see the run row the job commits.
     * The read-back is a lone committed-read; a missing row after a terminal run is a
     * programming error, not a client outcome.
     */
    @Override
    @PreAuthorize("hasRole('LEDGER_ADMIN')")
    public ReconciliationRun trigger(TriggerReconciliationCommand command) {
        UUID runId = ids.nextId();
        trigger.start(runId, command.triggeredBy());
        return reconciliation.findRun(runId).orElseThrow(() -> new IllegalStateException(
                "reconciliation run " + runId + " left no record — the job's listeners must "
                        + "open and close every run"));
    }

    @Override
    @PreAuthorize("hasRole('LEDGER_READ')")
    @Transactional(readOnly = true)
    public ReconciliationRun run(UUID runId) {
        return reconciliation.findRun(runId)
                .orElseThrow(() -> new ReconciliationRunNotFound(runId));
    }

    @Override
    @PreAuthorize("hasRole('LEDGER_READ')")
    @Transactional(readOnly = true)
    public ReconciliationFindingsPage findings(UUID runId, PageSpec page) {
        // Existence first: an unknown run must 404, not answer an authoritative empty page.
        if (reconciliation.findRun(runId).isEmpty()) {
            throw new ReconciliationRunNotFound(runId);
        }
        return reconciliation.findings(runId, page);
    }
}
