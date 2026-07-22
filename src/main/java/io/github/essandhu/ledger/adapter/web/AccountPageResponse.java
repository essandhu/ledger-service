package io.github.essandhu.ledger.adapter.web;

import java.util.List;

import io.github.essandhu.ledger.application.port.in.AccountPage;

/** Offset-pagination envelope for GET /accounts (PLAN §5). */
record AccountPageResponse(List<AccountResponse> content, int page, int size, long totalElements) {

    static AccountPageResponse from(AccountPage accountPage) {
        return new AccountPageResponse(
                accountPage.content().stream().map(AccountResponse::from).toList(),
                accountPage.page(), accountPage.size(), accountPage.totalElements());
    }
}
