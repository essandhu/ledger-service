package io.github.essandhu.ledger.support.fakes;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.essandhu.ledger.application.port.in.AccountFilter;
import io.github.essandhu.ledger.application.port.in.AccountNotFound;
import io.github.essandhu.ledger.application.port.in.AccountPage;
import io.github.essandhu.ledger.application.port.in.PageSpec;
import io.github.essandhu.ledger.application.port.out.AccountRepository;
import io.github.essandhu.ledger.domain.model.Account;
import io.github.essandhu.ledger.domain.model.AccountId;

/**
 * Hand-written fake (TEST-STRATEGY §2.1: fakes over mock-framework stubs, so the port contract
 * is enforced, not just echoed): a real in-memory implementation with the same observable
 * semantics as the JPA adapter — including id-ordered listing and failure on updating a missing
 * row. Counts writes so no-op detection is assertable.
 */
public final class FakeAccountRepository implements AccountRepository {

    private final Map<AccountId, Account> rows = new LinkedHashMap<>();
    private int updateCalls;

    @Override
    public void insert(Account account) {
        if (rows.putIfAbsent(account.id(), account) != null) {
            throw new IllegalStateException("duplicate insert for " + account.id());
        }
    }

    @Override
    public void update(Account account) {
        updateCalls++;
        if (rows.replace(account.id(), account) == null) {
            throw new AccountNotFound(account.id());
        }
    }

    @Override
    public Optional<Account> findById(AccountId id) {
        return Optional.ofNullable(rows.get(id));
    }

    @Override
    public AccountPage findAll(AccountFilter filter, PageSpec page) {
        List<Account> matching = rows.values().stream()
                .filter(a -> filter.type().map(t -> a.type() == t).orElse(true))
                .filter(a -> filter.status().map(s -> a.status() == s).orElse(true))
                .sorted(Comparator.comparing(a -> a.id().value()))
                .toList();
        List<Account> content = matching.stream()
                .skip((long) page.page() * page.size())
                .limit(page.size())
                .toList();
        return new AccountPage(content, page.page(), page.size(), matching.size());
    }

    /** Test-only seeding that bypasses the use-case layer. */
    public void seed(Account account) {
        rows.put(account.id(), account);
    }

    public int updateCalls() {
        return updateCalls;
    }
}
