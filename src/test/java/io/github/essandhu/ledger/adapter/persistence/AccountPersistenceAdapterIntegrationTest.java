package io.github.essandhu.ledger.adapter.persistence;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.essandhu.ledger.application.port.in.AccountFilter;
import io.github.essandhu.ledger.application.port.in.AccountNotFound;
import io.github.essandhu.ledger.application.port.in.AccountPage;
import io.github.essandhu.ledger.application.port.in.PageSpec;
import io.github.essandhu.ledger.application.port.out.AccountRepository;
import io.github.essandhu.ledger.application.port.out.BalanceRepository;
import io.github.essandhu.ledger.domain.model.Account;
import io.github.essandhu.ledger.domain.model.AccountId;
import io.github.essandhu.ledger.domain.model.AccountStatus;
import io.github.essandhu.ledger.domain.model.AccountType;
import io.github.essandhu.ledger.domain.model.CurrencyCode;
import io.github.essandhu.ledger.support.LedgerIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The account adapter edges the API tests do not reach: {@code update()} of a row that never
 * existed is the port's {@link AccountNotFound} (the path-addressed 404 contract, distinct
 * from the optimistic-lock 409 {@link OptimisticLockIntegrationTest} proves), and the two
 * SINGLE-filter listing branches — type alone, status alone — which the web layer's combined
 * filters skip past. Shared-context discipline: dedicated marker accounts, presence asserted
 * over the whole paged listing (never page one, never exact counts), and every filter promise
 * checked on every returned row regardless of which test created it.
 */
@LedgerIntegrationTest
@DisplayName("Account adapter edges: update-of-missing and the single-filter listing branches")
class AccountPersistenceAdapterIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-07-25T08:00:00Z");

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private BalanceRepository balances;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    @DisplayName("update() of an account that was never saved throws AccountNotFound — the port's 404 contract")
    void update_of_a_missing_account_throws_account_not_found() {
        Account ghost = Account.open(new AccountId(UUID.randomUUID()),
                "acct-adapter-ghost-" + UUID.randomUUID(), new CurrencyCode("EUR"),
                AccountType.ASSET, false, T0);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(
                tx -> accounts.update(ghost)))
                .isInstanceOf(AccountNotFound.class);
    }

    @Test
    @DisplayName("listing by TYPE alone finds the new account, and every returned row carries that type")
    void listing_by_type_only_matches_the_type_filter() {
        AccountId id = insertWithZeroBalance(Account.open(new AccountId(UUID.randomUUID()),
                "acct-list-type-" + UUID.randomUUID(), new CurrencyCode("EUR"),
                AccountType.LIABILITY, false, T0));
        AccountFilter typeOnly =
                new AccountFilter(Optional.of(AccountType.LIABILITY), Optional.empty());

        List<Account> listed = pageThrough(typeOnly);

        assertThat(listed).extracting(Account::id).contains(id);
        assertThat(listed).allSatisfy(account ->
                assertThat(account.type()).isEqualTo(AccountType.LIABILITY));
    }

    @Test
    @DisplayName("listing by STATUS alone finds the new frozen account, and every returned row carries that status")
    void listing_by_status_only_matches_the_status_filter() {
        // Inserted already FROZEN via the legal ACTIVE → FROZEN edge (I12): the adapter
        // persists whatever lifecycle state the domain produced.
        AccountId id = insertWithZeroBalance(Account.open(new AccountId(UUID.randomUUID()),
                "acct-list-status-" + UUID.randomUUID(), new CurrencyCode("EUR"),
                AccountType.ASSET, false, T0)
                .transitionTo(AccountStatus.FROZEN, T0));
        AccountFilter statusOnly =
                new AccountFilter(Optional.empty(), Optional.of(AccountStatus.FROZEN));

        List<Account> listed = pageThrough(statusOnly);

        assertThat(listed).extracting(Account::id).contains(id);
        assertThat(listed).allSatisfy(account ->
                assertThat(account.status()).isEqualTo(AccountStatus.FROZEN));
    }

    /** Port-level fixture: account + zero snapshot row in one transaction — the V2
     * forward-contract every below-use-case fixture honors (the
     * {@link OptimisticLockIntegrationTest} promise). */
    private AccountId insertWithZeroBalance(Account account) {
        transactionTemplate.executeWithoutResult(tx -> {
            accounts.insert(account);
            balances.insertZero(account.id(), T0);
        });
        return account.id();
    }

    /**
     * Walks the offset pages to exhaustion. The shared schema may hold any number of matching
     * rows from other tests — and id order is creation order (UUIDv7), so THIS test's account
     * sits on the LAST page; asserting presence on page one would be flaky by design.
     */
    private List<Account> pageThrough(AccountFilter filter) {
        List<Account> all = new ArrayList<>();
        for (int page = 0; page < 10_000; page++) {
            int current = page;
            AccountPage result = transactionTemplate.execute(
                    tx -> accounts.findAll(filter, new PageSpec(current, PageSpec.MAX_SIZE)));
            all.addAll(result.content());
            if (result.content().size() < PageSpec.MAX_SIZE) {
                return all;
            }
        }
        throw new IllegalStateException("account listing did not terminate within 10000 pages");
    }
}
