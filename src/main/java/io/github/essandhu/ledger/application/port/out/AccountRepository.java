package io.github.essandhu.ledger.application.port.out;

import java.util.Collection;
import java.util.List;
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

    /**
     * The accounts for the given ids, in no guaranteed order; an id without a row is simply
     * absent. The posting engine calls this ONLY after taking the account_balance locks — it
     * is the single status read of the critical section (ADR-0003), so a concurrent freeze or
     * close can never be half-seen. Unknown accounts were already detected from the lock
     * result (missing snapshot rows), which is why absence needs no error here.
     */
    List<Account> findByIds(Collection<AccountId> ids);

    /** Id-ordered (= creation-ordered, ids being UUIDv7) filtered page. */
    AccountPage findAll(AccountFilter filter, PageSpec page);
}
