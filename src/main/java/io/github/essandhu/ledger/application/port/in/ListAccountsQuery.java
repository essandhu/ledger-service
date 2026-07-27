package io.github.essandhu.ledger.application.port.in;

/** GET /accounts?type=&status=&page=&size= (the API contract; offset pagination is fine here). */
public interface ListAccountsQuery {

    AccountPage list(AccountFilter filter, PageSpec page);
}
