package io.github.essandhu.ledger.domain.model;

import java.time.Instant;
import java.util.Objects;

import io.github.essandhu.ledger.domain.error.ZeroAmountPosting;

/**
 * One leg of a posted entry: a signed amount in minor units, debit-positive, against one
 * account. Immutable and mutator-free — layer 1 of I3 (layer 2 is the
 * {@code @Immutable} JPA mapping, layer 3 the absent UPDATE/DELETE grants). Carries its
 * entry's {@code postedAt} denormalized, mirroring the {@code posting.posted_at} column
 * (posting rows answer M3's statement and as-of queries without joining the
 * header). Zero amounts are rejected here as well as in the draft (I2) — the
 * {@code posting_amount_nonzero} CHECK is the mirror at rest.
 */
public record Posting(
        PostingId id,
        EntryId entryId,
        AccountId accountId,
        Money amount,
        Instant postedAt) {

    public Posting {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(entryId, "entryId");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(postedAt, "postedAt");
        if (amount.isZero()) {
            throw new ZeroAmountPosting(accountId);
        }
    }
}
