package io.github.essandhu.ledger.application.port.in;

import java.util.List;

import io.github.essandhu.ledger.application.port.out.ReconciliationFinding;

/** One page of a run's id-ordered findings (UUIDv7 ids ⇒ id order is detection order). */
public record ReconciliationFindingsPage(
        List<ReconciliationFinding> content, int page, int size, long totalElements) {

    public ReconciliationFindingsPage {
        content = List.copyOf(content);
    }
}
