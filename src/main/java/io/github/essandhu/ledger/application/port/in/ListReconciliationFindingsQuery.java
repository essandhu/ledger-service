package io.github.essandhu.ledger.application.port.in;

import java.util.UUID;

/**
 * GET /reconciliation-runs/{id}/findings (M6): a run's drifted accounts, id-ordered,
 * offset-paginated (the account-listing posture: findings are a bounded read-your-report
 * listing, not an append-racing statement — keyset would be ceremony here). Unknown RUN →
 * {@link ReconciliationRunNotFound} (404); a known run with no findings is an empty page, not
 * an error.
 */
public interface ListReconciliationFindingsQuery {

    ReconciliationFindingsPage findings(UUID runId, PageSpec page);
}
