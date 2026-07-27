package io.github.essandhu.ledger.application.port.in;

import io.github.essandhu.ledger.domain.model.AccountId;

/**
 * {@code GET /api/v1/accounts/{id}/postings}: one account's statement — the
 * chronological excerpt of its postings — keyset-paginated on {@code (posted_at, id)}, stable
 * under concurrent appends (new postings only ever sort after every position already handed
 * out).
 */
public interface GetStatementQuery {

    /**
     * @throws AccountNotFound if no such account exists (the path addresses the account, so a
     *         miss is the 404, per the M1 doctrine — even though the answer for a known-empty
     *         account would also be an empty page)
     */
    StatementPage statement(AccountId id, StatementFilter filter, StatementSpec spec);
}
