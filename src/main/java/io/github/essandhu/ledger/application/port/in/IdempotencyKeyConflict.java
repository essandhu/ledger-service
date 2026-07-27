package io.github.essandhu.ledger.application.port.in;

import java.util.Objects;

/**
 * ADR-0004, I9: the same (principal, key) arrived with a DIFFERENT payload hash than the
 * recorded success — key reuse, a defect in the caller that the ledger rejects loudly (422,
 * {@code idempotency-key-conflict}) rather than masks. Nothing is posted and nothing is
 * written: the conflict is decided before any lock or write, and a conflict discovered by
 * losing an insert race is decided in a fresh transaction after the loser's rolled back.
 *
 * <p>Lives in {@code port.in} beside {@link AccountNotFound}/{@link EntryNotFound}: idempotency
 * is application protocol, not a domain money rule — {@code domain.error} stays the vocabulary
 * of double-entry itself. Deliberately NOT part of the {@code ledger.posting.rejected} reason
 * vocabulary: the metrics contract gives conflicts their own counter, {@code ledger.idempotency.conflict}
 * (the {@code account-balance-not-zero} exclusion precedent).
 */
public class IdempotencyKeyConflict extends RuntimeException {

    private final String idempotencyKey;

    public IdempotencyKeyConflict(String idempotencyKey) {
        super(("idempotency key '%s' was already used by this principal for a different "
                + "request; keys must be unique per logical operation (ADR-0004)")
                .formatted(idempotencyKey));
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }
}
