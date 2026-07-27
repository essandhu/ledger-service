package io.github.essandhu.ledger.adapter.web;

import java.util.List;

import io.github.essandhu.ledger.application.port.in.ReconciliationFindingsPage;

/** Offset-pagination envelope for GET /reconciliation-runs/{id}/findings (M6). */
record ReconciliationFindingsPageResponse(
        List<ReconciliationFindingResponse> content, int page, int size, long totalElements) {

    static ReconciliationFindingsPageResponse from(ReconciliationFindingsPage findingsPage) {
        return new ReconciliationFindingsPageResponse(
                findingsPage.content().stream().map(ReconciliationFindingResponse::from).toList(),
                findingsPage.page(), findingsPage.size(), findingsPage.totalElements());
    }
}
