package io.github.essandhu.ledger.application.port.in;

import java.time.Instant;

import io.github.essandhu.ledger.domain.model.AccountId;

/**
 * {@code GET /api/v1/accounts/{id}/balance} (PLAN §5): the two balance reads of ADR-0002 as
 * two methods, deliberately — they are different data paths with different consistency
 * stories, and folding them behind one signature would blur exactly the split the ADR pins.
 */
public interface GetBalanceQuery {

    /**
     * The live balance: the maintained snapshot row, read lock-free and O(1) regardless of
     * account history (ADR-0002).
     *
     * @throws AccountNotFound if no such account exists
     */
    BalanceView current(AccountId id);

    /**
     * The balance at {@code at}: Σ postings with {@code posted_at <= at}, never the snapshot
     * (ADR-0002, I10). Exact once every posting transaction that could assign a
     * {@code posted_at <= at} has committed — per-account posted_at order equals commit order
     * by construction (PLAN §4.6), so a committed answer never reorders or loses postings; a
     * read taken while such writes are in flight is merely provisional and may gain their
     * postings on re-read. {@code at} may lie in the future: the sum is well-defined and
     * monotone in {@code at}, just provisional until that instant passes.
     *
     * @throws AccountNotFound if no such account exists
     */
    BalanceView asOf(AccountId id, Instant at);
}
