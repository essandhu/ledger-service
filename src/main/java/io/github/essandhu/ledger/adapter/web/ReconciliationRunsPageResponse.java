package io.github.essandhu.ledger.adapter.web;

import java.util.List;

import io.github.essandhu.ledger.application.port.in.ReconciliationRunPage;

/** Offset-pagination envelope for GET /reconciliation-runs — newest first (M8c). */
record ReconciliationRunsPageResponse(
        List<ReconciliationRunResponse> content, int page, int size, long totalElements) {

    static ReconciliationRunsPageResponse from(ReconciliationRunPage runPage) {
        return new ReconciliationRunsPageResponse(
                runPage.content().stream().map(ReconciliationRunResponse::from).toList(),
                runPage.page(), runPage.size(), runPage.totalElements());
    }
}
