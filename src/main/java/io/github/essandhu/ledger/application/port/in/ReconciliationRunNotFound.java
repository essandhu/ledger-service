package io.github.essandhu.ledger.application.port.in;

import java.util.UUID;

/**
 * A path-addressed reconciliation run that does not exist — use-case contract, not a domain
 * rule violation, hence this package and its 404 mapping (the {@link AccountNotFound} shape).
 */
public class ReconciliationRunNotFound extends RuntimeException {

    public ReconciliationRunNotFound(UUID runId) {
        super("no reconciliation run " + runId);
    }
}
