package io.github.essandhu.ledger.application.port.in;

import java.util.List;

import io.github.essandhu.ledger.application.port.out.ReconciliationRun;

/**
 * One page of the run history, NEWEST FIRST (UUIDv7 ids ⇒ descending id order is reverse
 * chronological) — the one listing in the API that does not ascend, because an append-only
 * operational log is read from its newest end: "what did the last sweep say?" must be page 0,
 * not the last page of an unknown number.
 */
public record ReconciliationRunPage(
        List<ReconciliationRun> content, int page, int size, long totalElements) {

    public ReconciliationRunPage {
        content = List.copyOf(content);
    }
}
