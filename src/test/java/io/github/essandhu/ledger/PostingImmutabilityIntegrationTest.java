package io.github.essandhu.ledger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.github.essandhu.ledger.support.LedgerIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * I3 (M2): the ledger is append-only at the database level. What denies a history rewrite is the
 * runtime role's missing UPDATE/DELETE grants — not application code — so every probe here runs
 * through the application's own pool and expects the database's privilege error (PLAN's M2
 * acceptance: "try to UPDATE a posting and get a privilege error"). The positive INSERT probes
 * prove the denials are the grant model at work rather than a broken pool or a missing table:
 * the very connection that can record an entry cannot rewrite one.
 *
 * <p>Grant-set inspection (exact ACLs via {@code aclexplode}) lives in
 * {@link JournalSchemaIntegrationTest}; this class is the behavioral half of the same proof.
 */
@LedgerIntegrationTest
@DisplayName("I3: journal_entry and posting are immutable through the application pool")
class PostingImmutabilityIntegrationTest {

    /** The application pool — connects as the runtime role {@code ledger_app}. */
    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("I3: the runtime role cannot UPDATE journal_entry — denied by the database, not by code")
    void runtime_role_cannot_update_journal_entry() throws SQLException {
        // The privilege check fires before row matching, so the probe is additive-safe: 42501
        // with a random id whether or not any row exists. If the grant were wrongly present,
        // the statement would succeed against zero rows — and the absent exception fails the test.
        try (Connection connection = dataSource.getConnection();
             PreparedStatement update = connection.prepareStatement(
                     "UPDATE journal_entry SET description = 'rewritten' WHERE id = ?")) {
            update.setObject(1, UUID.randomUUID());
            assertThatThrownBy(update::executeUpdate)
                    .isInstanceOf(SQLException.class)
                    .hasFieldOrPropertyWithValue("SQLState", "42501"); // insufficient_privilege
        }
    }

    @Test
    @DisplayName("I3: the runtime role cannot DELETE from journal_entry — denied by the database, not by code")
    void runtime_role_cannot_delete_from_journal_entry() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement delete = connection.prepareStatement(
                     "DELETE FROM journal_entry WHERE id = ?")) {
            delete.setObject(1, UUID.randomUUID());
            assertThatThrownBy(delete::executeUpdate)
                    .isInstanceOf(SQLException.class)
                    .hasFieldOrPropertyWithValue("SQLState", "42501");
        }
    }

    @Test
    @DisplayName("I3: the runtime role cannot UPDATE posting — the PLAN M2 acceptance probe, verbatim")
    void runtime_role_cannot_update_posting() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement update = connection.prepareStatement(
                     "UPDATE posting SET amount = 0 WHERE id = ?")) {
            update.setObject(1, UUID.randomUUID());
            assertThatThrownBy(update::executeUpdate)
                    .isInstanceOf(SQLException.class)
                    .hasFieldOrPropertyWithValue("SQLState", "42501");
        }
    }

    @Test
    @DisplayName("I3: the runtime role cannot DELETE from posting — denied by the database, not by code")
    void runtime_role_cannot_delete_from_posting() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement delete = connection.prepareStatement(
                     "DELETE FROM posting WHERE id = ?")) {
            delete.setObject(1, UUID.randomUUID());
            assertThatThrownBy(delete::executeUpdate)
                    .isInstanceOf(SQLException.class)
                    .hasFieldOrPropertyWithValue("SQLState", "42501");
        }
    }

    @Test
    @DisplayName("I3: the runtime role CAN INSERT an entry and its postings — append works, so the denials are the grants")
    void runtime_role_can_insert_entry_and_postings() throws SQLException {
        // Everything through the app pool as raw SQL: ledger_app holds INSERT on account (V2)
        // and on all three V3 tables, and the posting API does not exist yet at this layer of
        // the build. Marker names keep the rows additive-safe (TEST-STRATEGY §2).
        UUID accountId = UUID.randomUUID();
        UUID entryId = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO account (id, name, currency, type, status, allow_negative,
                                         version, created_at, updated_at)
                    VALUES (?, ?, 'USD', 'ASSET', 'ACTIVE', false, 0, now(), now())
                    """)) {
                insert.setObject(1, accountId);
                insert.setString(2, "posting-immutability-probe-" + accountId);
                assertThat(insert.executeUpdate()).isEqualTo(1);
            }
            // Header satisfying the reversal-shape CHECK: a plain JOURNAL entry, reversal_of NULL.
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO journal_entry (id, entry_type, description, reversal_of,
                                               idempotency_key, created_by, posted_at)
                    VALUES (?, 'JOURNAL', NULL, NULL, NULL, ?, now())
                    """)) {
                insert.setObject(1, entryId);
                insert.setString(2, "posting-immutability-probe-" + entryId);
                assertThat(insert.executeUpdate()).isEqualTo(1);
            }
            // Two balanced legs (+100 / −100) on the one account: the I1 zero-sum shape, so this
            // marker pair never disturbs the global per-currency conservation (I5) that other
            // tests assert by SQL. posted_at COPIES the header's instant rather than calling
            // now() again — in autocommit each statement gets its own now(), and since M6 the
            // reconciliation sweep re-verifies the posted_at denormalization at rest (PLAN
            // §4.3): a fixture with a mismatched leg would be flagged as corruption, correctly.
            for (long amount : new long[] {100, -100}) {
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO posting (id, entry_id, account_id, amount, currency, posted_at)
                        SELECT ?, id, ?, ?, 'USD', posted_at FROM journal_entry WHERE id = ?
                        """)) {
                    insert.setObject(1, UUID.randomUUID());
                    insert.setObject(2, accountId);
                    insert.setLong(3, amount);
                    insert.setObject(4, entryId);
                    assertThat(insert.executeUpdate()).isEqualTo(1);
                }
            }
            // The snapshot row, last, once its numbers are true: raw fixtures honor by hand what
            // the M2 posting transaction honors in code — balance = Σ legs = 0, posting_count = 2
            // (I4, which the M6 sweep now asserts at rest for EVERY account), updated_at = the
            // legs' posted_at (the ADR-0002 applyDelta contract), and no account without a
            // balance row (the ADR-0003 lock-protocol precondition that
            // JournalSchemaIntegrationTest asserts globally).
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO account_balance (account_id, balance, posting_count, updated_at)
                    SELECT ?, 0, 2, posted_at FROM journal_entry WHERE id = ?
                    """)) {
                insert.setObject(1, accountId);
                insert.setObject(2, entryId);
                assertThat(insert.executeUpdate()).isEqualTo(1);
            }
        }
    }
}
