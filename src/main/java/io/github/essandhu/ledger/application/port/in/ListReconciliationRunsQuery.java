package io.github.essandhu.ledger.application.port.in;

/**
 * GET /reconciliation-runs?page=&size= (M8c): the append-only run history, newest first,
 * offset-paginated (the account-listing posture — a bounded audit read, not an append-racing
 * statement). Added for the console's run-history page: without a collection GET the sweep
 * record was reachable only by an id you had to already know.
 */
public interface ListReconciliationRunsQuery {

    ReconciliationRunPage runs(PageSpec page);
}
