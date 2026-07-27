package io.github.essandhu.ledger.adapter.web;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.github.essandhu.ledger.application.port.out.ReconciliationRun;

/**
 * One reconciliation run on the wire (PLAN §5, M6). {@code finishedAt} and the result counts
 * are absent — never null — until the run has them (the BalanceResponse asOf posture): a
 * RUNNING row observed mid-sweep and a FAILED row simply have less to say.
 */
record ReconciliationRunResponse(
        UUID id,
        String status,
        Instant startedAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) Instant finishedAt,
        String triggeredBy,
        @JsonInclude(JsonInclude.Include.NON_NULL) Long accountsChecked,
        @JsonInclude(JsonInclude.Include.NON_NULL) Long driftCount,
        @JsonInclude(JsonInclude.Include.NON_NULL) Long currencyMismatchCount,
        @JsonInclude(JsonInclude.Include.NON_NULL) Long postedAtMismatchCount,
        @JsonInclude(JsonInclude.Include.NON_NULL) Long unbalancedCurrencyCount) {

    static ReconciliationRunResponse from(ReconciliationRun run) {
        ReconciliationRun.Results results = run.results().orElse(null);
        return new ReconciliationRunResponse(
                run.id(),
                run.status().name(),
                run.startedAt(),
                run.finishedAt().orElse(null),
                run.triggeredBy(),
                results == null ? null : results.accountsChecked(),
                results == null ? null : results.driftCount(),
                results == null ? null : results.currencyMismatchCount(),
                results == null ? null : results.postedAtMismatchCount(),
                results == null ? null : results.unbalancedCurrencyCount());
    }
}
