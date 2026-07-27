package io.github.essandhu.ledger.application.port.in;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import io.github.essandhu.ledger.domain.model.AccountId;
import io.github.essandhu.ledger.domain.model.AccountType;
import io.github.essandhu.ledger.domain.model.CurrencyCode;

/**
 * Result of {@link GetBalanceQuery}: one account's balance in both pinned readings — raw (the
 * signed Σ of postings, debit-positive) and natural (raw × direction, the sign convention: the figure
 * "shown to clients"). {@code postingCount} is the number of postings the figure aggregates,
 * exposed so a client can verify a full statement walk against the balance it reconciles to
 * (pinned at M3). {@code asOf} present ⇔ the figure was derived from postings at that
 * instant (ADR-0002); empty ⇔ the live snapshot.
 */
public record BalanceView(
        AccountId accountId,
        AccountType type,
        CurrencyCode currency,
        long raw,
        long natural,
        long postingCount,
        Optional<Instant> asOf) {

    public BalanceView {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(asOf, "asOf");
        if (postingCount < 0) {
            throw new IllegalArgumentException("postingCount must be >= 0, got " + postingCount);
        }
    }
}
