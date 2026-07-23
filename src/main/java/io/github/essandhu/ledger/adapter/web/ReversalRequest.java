package io.github.essandhu.ledger.adapter.web;

import jakarta.validation.constraints.Size;

/**
 * POST /journal-entries/{id}/reversal body (PLAN §5) — optional in its entirety: a reversal
 * needs nothing beyond the path id, and the description it may carry is the REVERSING entry's
 * own (the original's is immutable, I3). The controller maps an absent body to an absent
 * description.
 */
record ReversalRequest(@Size(max = 500) String description) {
}
