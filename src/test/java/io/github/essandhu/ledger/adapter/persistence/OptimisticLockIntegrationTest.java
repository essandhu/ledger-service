package io.github.essandhu.ledger.adapter.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.essandhu.ledger.application.port.out.AccountRepository;
import io.github.essandhu.ledger.domain.model.Account;
import io.github.essandhu.ledger.domain.model.AccountId;
import io.github.essandhu.ledger.domain.model.AccountType;
import io.github.essandhu.ledger.domain.model.CurrencyCode;
import io.github.essandhu.ledger.support.LedgerIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The half of the 409 proof that needs a real database: {@code @Version} is an in-flight
 * overlap guard. Two transactions that overlap in time on the same account row — here, a
 * use-case-style read-modify-write racing a committed out-of-band bump — end with the loser
 * raising {@link OptimisticLockingFailureException}, which the web advice maps to 409 (that
 * mapping is unit-proven in {@code LedgerExceptionHandlerTest}). Sequential PATCHes never
 * conflict (each re-reads fresh state): last-write-wins there is accepted and bounded by the
 * I12 state machine — a stale write cannot resurrect CLOSED, because the losing transaction's
 * re-read sees CLOSED and is re-rejected.
 */
@LedgerIntegrationTest
@DisplayName("@Version: overlapping writers conflict — 409's database half")
class OptimisticLockIntegrationTest {

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("a write based on a stale read fails with OptimisticLockingFailureException")
    void stale_write_conflicts() {
        AccountId id = new AccountId(UUID.randomUUID());
        Account account = Account.open(id, "lock-probe-" + id.value(), new CurrencyCode("EUR"),
                AccountType.ASSET, false, Instant.parse("2026-07-22T10:00:00Z"));
        transactionTemplate.executeWithoutResult(tx -> accounts.insert(account));

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(tx -> {
            // Read inside the transaction (loads version N into the persistence context) ...
            Account current = accounts.findById(id).orElseThrow();
            // ... a concurrent writer commits a version bump on a separate connection ...
            bumpVersionOutOfBand(id);
            // ... and our write, computed from the now-stale read, must lose.
            accounts.update(current.rename("renamed-" + id.value(),
                    Instant.parse("2026-07-22T10:01:00Z")));
        })).isInstanceOf(OptimisticLockingFailureException.class);

        String persistedName =
                transactionTemplate.execute(tx -> accounts.findById(id).orElseThrow().name());
        assertThat(persistedName)
                .as("the stale rename must not have been applied")
                .startsWith("lock-probe-");
    }

    private void bumpVersionOutOfBand(AccountId id) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement update = connection.prepareStatement(
                     "UPDATE account SET version = version + 1, updated_at = now() WHERE id = ?")) {
            update.setObject(1, id.value());
            assertThat(update.executeUpdate()).isEqualTo(1);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
