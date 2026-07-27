package io.github.essandhu.ledger.adapter.web;

import java.net.URI;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.essandhu.ledger.application.port.in.GetReconciliationRunQuery;
import io.github.essandhu.ledger.application.port.in.ListReconciliationFindingsQuery;
import io.github.essandhu.ledger.application.port.in.PageSpec;
import io.github.essandhu.ledger.application.port.in.ReconcileBalancesUseCase;
import io.github.essandhu.ledger.application.port.in.ReconcileBalancesUseCase.TriggerReconciliationCommand;
import io.github.essandhu.ledger.application.port.out.ReconciliationRun;

/**
 * The reconciliation surface (M6). Thin: DTO ↔ command mapping only. POST runs the
 * sweep synchronously and answers 201 with the run RESOURCE — whatever its verdict, including
 * FAILED: the run row exists and is the honest answer, and "the sweep could not finish" is the
 * resource's state, not a transport error. No {@code Idempotency-Key}: not a money mover;
 * every trigger legitimately starts a fresh sweep (the account-management natural-retry
 * posture).
 */
@RestController
@RequestMapping("/api/v1/reconciliation-runs")
class ReconciliationController {

    private final ReconcileBalancesUseCase reconcile;
    private final GetReconciliationRunQuery getRun;
    private final ListReconciliationFindingsQuery listFindings;

    ReconciliationController(ReconcileBalancesUseCase reconcile,
            GetReconciliationRunQuery getRun, ListReconciliationFindingsQuery listFindings) {
        this.reconcile = reconcile;
        this.getRun = getRun;
        this.listFindings = listFindings;
    }

    @PostMapping
    ResponseEntity<ReconciliationRunResponse> trigger(@AuthenticationPrincipal Jwt jwt) {
        String triggeredBy = MissingTokenSubject.requiredSubject(jwt);
        ReconciliationRun run =
                reconcile.trigger(new TriggerReconciliationCommand(triggeredBy));
        return ResponseEntity
                .created(URI.create("/api/v1/reconciliation-runs/" + run.id()))
                .body(ReconciliationRunResponse.from(run));
    }

    @GetMapping("/{id}")
    ReconciliationRunResponse byId(@PathVariable UUID id) {
        return ReconciliationRunResponse.from(getRun.run(id));
    }

    @GetMapping("/{id}/findings")
    ReconciliationFindingsPageResponse findings(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(PageSpec.MAX_SIZE) int size) {
        return ReconciliationFindingsPageResponse.from(
                listFindings.findings(id, new PageSpec(page, size)));
    }
}
