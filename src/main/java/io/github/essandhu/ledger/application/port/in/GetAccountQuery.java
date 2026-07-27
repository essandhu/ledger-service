package io.github.essandhu.ledger.application.port.in;

import io.github.essandhu.ledger.domain.model.Account;
import io.github.essandhu.ledger.domain.model.AccountId;

/** GET /accounts/{id}. */
public interface GetAccountQuery {

    /** @throws AccountNotFound if no such account exists */
    Account byId(AccountId id);
}
