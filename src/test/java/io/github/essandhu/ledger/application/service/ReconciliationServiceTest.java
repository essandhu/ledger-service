package io.github.essandhu.ledger.application.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.essandhu.ledger.application.port.in.PageSpec;
import io.github.essandhu.ledger.application.port.in.ReconcileBalancesUseCase.TriggerReconciliationCommand;
import io.github.essandhu.ledger.application.port.in.ReconciliationRunNotFound;
import io.github.essandhu.ledger.application.port.out.ReconciliationRun;
import io.github.essandhu.ledger.application.port.out.ReconciliationTrigger;
import io.github.essandhu.ledger.support.fakes.FakeReconciliationRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The thin caller-facing service: mints the run id, delegates the sweep to the trigger port,
 * reads the verdict back; 404-shaped misses for unknown runs; and (M8c) the run history,
 * newest first.
 */
@DisplayName("ReconciliationService: trigger, read-back, and the run history")
class ReconciliationServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-26T12:00:00Z");
    private static final UUID MINTED = UUID.fromString("00000000-0000-7000-8000-000000000007");

    private final FakeReconciliationRepository store = new FakeReconciliationRepository();
    private final ReconciliationRunService runService =
            new ReconciliationRunService(store, Clock.fixed(NOW, ZoneOffset.UTC));
    private final List<String> triggeredBy = new ArrayList<>();

    /** A trigger that behaves like the real job: opens and closes the run it was asked for. */
    private final ReconciliationTrigger completingTrigger = (runId, principal) -> {
        triggeredBy.add(principal);
        runService.openRun(runId, principal);
        runService.closeRun(runId, 5);
    };

    private ReconciliationService service(ReconciliationTrigger trigger) {
        return new ReconciliationService(trigger, store, () -> MINTED);
    }

    @Test
    @DisplayName("trigger mints the run id, passes the principal, and returns the recorded verdict")
    void trigger_returns_the_recorded_run() {
        ReconciliationRun run = service(completingTrigger)
                .trigger(new TriggerReconciliationCommand("alice"));
        assertThat(run.id()).isEqualTo(MINTED);
        assertThat(run.status()).isEqualTo(ReconciliationRun.Status.CLEAN);
        assertThat(run.triggeredBy()).isEqualTo("alice");
        assertThat(triggeredBy).containsExactly("alice");
    }

    @Test
    @DisplayName("a trigger that leaves no record is a programming error, not a client outcome")
    void trigger_without_record_fails_loudly() {
        ReconciliationService broken = service((runId, principal) -> { });
        assertThatIllegalStateException()
                .isThrownBy(() -> broken.trigger(new TriggerReconciliationCommand("alice")))
                .withMessageContaining("left no record");
    }

    @Test
    @DisplayName("run(): the record, or ReconciliationRunNotFound for an unknown id")
    void run_lookup() {
        service(completingTrigger).trigger(new TriggerReconciliationCommand("alice"));
        ReconciliationService reads = service(completingTrigger);
        assertThat(reads.run(MINTED).status()).isEqualTo(ReconciliationRun.Status.CLEAN);
        assertThatThrownBy(() -> reads.run(UUID.randomUUID()))
                .isInstanceOf(ReconciliationRunNotFound.class);
    }

    @Test
    @DisplayName("runs(): the history pages NEWEST first — page 0 is the latest sweep, not the oldest")
    void runs_page_newest_first() {
        // Three runs in id order (UUIDv7 ⇒ chronological); the listing must invert it.
        List<UUID> chronological = List.of(
                UUID.fromString("00000000-0000-7000-8000-000000000001"),
                UUID.fromString("00000000-0000-7000-8000-000000000002"),
                UUID.fromString("00000000-0000-7000-8000-000000000003"));
        chronological.forEach(id -> {
            runService.openRun(id, "scheduler");
            runService.closeRun(id, 5);
        });

        ReconciliationService reads = service(completingTrigger);
        var first = reads.runs(new PageSpec(0, 2));
        assertThat(first.content()).extracting(ReconciliationRun::id)
                .containsExactly(chronological.get(2), chronological.get(1));
        assertThat(first.totalElements()).isEqualTo(3);
        // The second page continues descending — no run is skipped or repeated.
        assertThat(reads.runs(new PageSpec(1, 2)).content()).extracting(ReconciliationRun::id)
                .containsExactly(chronological.get(0));
    }

    @Test
    @DisplayName("runs(): no runs at all is an empty page, never an error")
    void runs_of_empty_history_is_empty_page() {
        var page = service(completingTrigger).runs(new PageSpec(0, 20));
        assertThat(page.content()).isEmpty();
        assertThat(page.totalElements()).isZero();
    }

    @Test
    @DisplayName("findings(): unknown run 404s FIRST — an authoritative empty page would be a lie")
    void findings_of_unknown_run_is_not_found() {
        ReconciliationService reads = service(completingTrigger);
        assertThatThrownBy(() -> reads.findings(UUID.randomUUID(), new PageSpec(0, 20)))
                .isInstanceOf(ReconciliationRunNotFound.class);
    }

    @Test
    @DisplayName("findings(): a known run with no findings is an empty page, not an error")
    void findings_of_clean_run_is_empty_page() {
        service(completingTrigger).trigger(new TriggerReconciliationCommand("alice"));
        var page = service(completingTrigger).findings(MINTED, new PageSpec(0, 20));
        assertThat(page.content()).isEmpty();
        assertThat(page.totalElements()).isZero();
    }
}
