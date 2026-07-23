package io.github.essandhu.ledger.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * The per-account balance snapshot as a domain value (PLAN §4.1, ADR-0002): the raw signed sum
 * of the account's posting amounts in minor units, debit-positive, plus the posting-count
 * watermark reconciliation compares against {@code COUNT(*)} (I15, I4). Raw MAY be negative —
 * on allow-negative accounts, and on any credit-normal account in ordinary credit: the
 * overdraft floor (I6) applies to the NATURAL balance, never the raw one, which is why the
 * {@code account_balance.balance} column carries no CHECK.
 *
 * <p>Maintained by the posting transaction under the ordered account_balance lock (ADR-0003);
 * this value is what the overdraft check and the close-balance rule ({@link CloseBalanceRule})
 * read while that lock is held. It is a snapshot, not history — as-of balances are always
 * derived from postings (ADR-0002).
 */
public record AccountBalance(
        AccountId accountId,
        long balance,
        long postingCount,
        Instant updatedAt) {

    public AccountBalance {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (postingCount < 0) {
            throw new IllegalArgumentException(
                    "postingCount must be >= 0, got %d (the watermark only grows, ADR-0002)"
                            .formatted(postingCount));
        }
    }

    /**
     * The balance a bookkeeper expects: {@code raw × direction(type)} (PLAN §4.2) — what
     * clients see, what the overdraft check floors at zero (I6), and what must be zero to
     * close (PLAN §4.5). Checked ({@code Math.multiplyExact}): direction is ±1, so the product
     * is exact for every representable balance bar the two's-complement edge — raw
     * {@code Long.MIN_VALUE} on a credit-normal account has no 64-bit natural (the same lone
     * edge {@code negateExact} refuses in reversal arithmetic), and a plain {@code *} would
     * silently return {@code Long.MIN_VALUE} again: a NEGATIVE verdict for an astronomically
     * positive position. The {@link ArithmeticException} deliberately propagates raw from this
     * type — translating it into the client-facing {@code AmountOverflow} rejection is the
     * accumulation points' job (posting's overdraft step, the close use case), exactly the
     * contract {@link Money} pins for its own checked arithmetic.
     */
    public long natural(AccountType type) {
        Objects.requireNonNull(type, "type");
        return type.natural(balance);
    }
}
