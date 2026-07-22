package io.github.essandhu.ledger.application.port.in;

import java.util.List;

import io.github.essandhu.ledger.domain.model.Account;

/** One page of an id-ordered account listing (UUIDv7 ids ⇒ id order is creation order). */
public record AccountPage(List<Account> content, int page, int size, long totalElements) {

    public AccountPage {
        content = List.copyOf(content);
    }
}
