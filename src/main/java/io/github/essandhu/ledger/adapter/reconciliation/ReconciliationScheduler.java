package io.github.essandhu.ledger.adapter.reconciliation;

import java.util.UUID;

import org.springframework.scheduling.annotation.Scheduled;

import io.github.essandhu.ledger.application.port.out.IdGenerator;
import io.github.essandhu.ledger.application.port.out.ReconciliationTrigger;

/**
 * The scheduled driver of the reconciliation sweep ("a scheduled Spring Batch job").
 * Calls the launcher directly rather than the admin use case: a schedule has no principal to
 * authorize, so it must not pass a {@code @PreAuthorize} gate (the IdempotencyPurger
 * precedent) — its runs are attributed to {@code "scheduler"} in the run row and the job
 * parameters. Only instantiated when the schedule is enabled
 * ({@code ReconciliationScheduleConfig}); v1 documents a single-app-instance assumption for
 * schedulers (adr/README future candidates) — two instances with this enabled would run
 * concurrent sweeps, which is harmless to correctness (each appends only its own findings)
 * but doubles the read load for nothing.
 */
final class ReconciliationScheduler {

    static final String SCHEDULER_PRINCIPAL = "scheduler";

    private final ReconciliationTrigger trigger;
    private final IdGenerator ids;

    ReconciliationScheduler(ReconciliationTrigger trigger, IdGenerator ids) {
        this.trigger = trigger;
        this.ids = ids;
    }

    /** One sweep; returns the run id (the scheduler ignores it, tests assert it). */
    @Scheduled(fixedDelayString = "${ledger.reconciliation.schedule.interval:PT1H}")
    public UUID reconcile() {
        UUID runId = ids.nextId();
        trigger.start(runId, SCHEDULER_PRINCIPAL);
        return runId;
    }
}
