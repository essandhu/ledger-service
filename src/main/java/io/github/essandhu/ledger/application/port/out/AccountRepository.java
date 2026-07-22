package io.github.essandhu.ledger.application.port.out;

import java.util.Optional;

import io.github.essandhu.ledger.application.port.in.AccountFilter;
import io.github.essandhu.ledger.application.port.in.AccountNotFound;
import io.github.essandhu.ledger.application.port.in.AccountPage;
import io.github.essandhu.ledger.application.port.in.PageSpec;
import io.github.essandhu.ledger.domain.model.Account;
import io.github.essandhu.ledger.domain.model.AccountId;

/** Driven port for account persistence; implemented by the JPA adapter. */
public interface AccountRepository {

    /** Persists a new account. Inserting an existing id is a programming error. */
    void insert(Account account);

    /**
     * Persists the new state of an existing account.
     *
     * @throws AccountNotFound if the row no longer exists
     */
    void update(Account account);

    Optional<Account> findById(AccountId id);

    /** Id-ordered (= creation-ordered, ids being UUIDv7) filtered page. */
    AccountPage findAll(AccountFilter filter, PageSpec page);
}
