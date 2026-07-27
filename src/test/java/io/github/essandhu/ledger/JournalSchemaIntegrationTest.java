package io.github.essandhu.ledger;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.postgresql.PostgreSQLContainer;

import io.github.essandhu.ledger.support.LedgerIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * I16 (M2): the V3 journal migration applies cleanly and establishes the append-only grant model
 * for the runtime role. Second prong of the two-pronged M0 pattern: V3 self-verifies its ACLs and
 * its backfill in every environment it runs in; this test independently re-proves the grants
 * through the application's own pool and probes every database-level rule the domain relies on as
 * defense in depth — CHECK constraints exercised as the table owner (a constraint that only stops
 * {@code ledger_app} proves too little; V2's rationale is that a row a DBA sneaks in can never be
 * one the domain refuses to load), the partial unique indexes (I11's at-most-once reversal and
 * ADR-0004's permanent idempotency backstop), and the global account-has-balance-row precondition
 * without which the ADR-0003 lock protocol dies.
 *
 * <p>The behavioral half of I3 — UPDATE/DELETE denied through the application pool — lives in
 * {@link PostingImmutabilityIntegrationTest}; this class pins the grant <em>sets</em>.
 */
@LedgerIntegrationTest
@DisplayName("I16: V3 journal migration, grants, and constraints")
class JournalSchemaIntegrationTest {

    /** The application pool — connects as the runtime role {@code ledger_app}. */
    @Autowired
    private DataSource dataSource;

    @Autowired
    private PostgreSQLContainer postgres;

    @Test
    @DisplayName("I16: V3 is recorded as applied in flyway history")
    void v3_journal_migration_is_recorded_in_flyway_history() throws SQLException {
        // flyway_schema_history is owned by ledger_migrate and not granted to the runtime role.
        try (Connection migrate = migrateConnection();
             Statement statement = migrate.createStatement();
             ResultSet history = statement.executeQuery(
                     "SELECT success FROM flyway_schema_history WHERE version = '3'")) {
            assertThat(history.next()).as("V3 migration recorded").isTrue();
            assertThat(history.getBoolean("success")).isTrue();
        }
    }

    @Test
    @DisplayName("I3: ledger_app's grants on journal_entry are EXACTLY {SELECT, INSERT} — history cannot be rewritten")
    void runtime_role_grants_on_journal_entry_are_exactly_select_insert() throws SQLException {
        assertThat(runtimeRoleGrantsOn("journal_entry")).containsExactlyInAnyOrder("SELECT", "INSERT");
    }

    @Test
    @DisplayName("I3: ledger_app's grants on posting are EXACTLY {SELECT, INSERT} — history cannot be rewritten")
    void runtime_role_grants_on_posting_are_exactly_select_insert() throws SQLException {
        assertThat(runtimeRoleGrantsOn("posting")).containsExactlyInAnyOrder("SELECT", "INSERT");
    }

    @Test
    @DisplayName("I16: ledger_app's grants on account_balance are EXACTLY {SELECT, INSERT, UPDATE} — snapshots move, never vanish")
    void runtime_role_grants_on_account_balance_are_exactly_select_insert_update() throws SQLException {
        assertThat(runtimeRoleGrantsOn("account_balance"))
                .containsExactlyInAnyOrder("SELECT", "INSERT", "UPDATE");
    }

    @Test
    @DisplayName("I2: a zero-amount posting is rejected by the database CHECK — even for the table owner")
    void zero_amount_posting_is_rejected_by_check() throws SQLException {
        try (Connection migrate = migrateConnection()) {
            UUID accountId = insertAccountWithBalance(migrate);
            UUID entryId = insertEntry(migrate, "JOURNAL", null, marker("creator"), null);
            try (PreparedStatement insert = migrate.prepareStatement("""
                    INSERT INTO posting (id, entry_id, account_id, amount, currency, posted_at)
                    VALUES (?, ?, ?, 0, 'USD', now())
                    """)) {
                insert.setObject(1, UUID.randomUUID());
                insert.setObject(2, entryId);
                insert.setObject(3, accountId);
                assertThatThrownBy(insert::executeUpdate)
                        .isInstanceOf(SQLException.class)
                        .hasFieldOrPropertyWithValue("SQLState", "23514"); // check_violation
            }
        }
    }

    @Test
    @DisplayName("journal_entry rejects entry types outside {TRANSFER, JOURNAL, REVERSAL}")
    void unknown_entry_type_is_rejected_by_check() throws SQLException {
        try (Connection migrate = migrateConnection();
             PreparedStatement insert = migrate.prepareStatement("""
                     INSERT INTO journal_entry (id, entry_type, description, reversal_of,
                                                idempotency_key, created_by, posted_at)
                     VALUES (?, 'ADJUSTMENT', NULL, NULL, NULL, ?, now())
                     """)) {
            insert.setObject(1, UUID.randomUUID());
            insert.setString(2, marker("creator"));
            assertThatThrownBy(insert::executeUpdate)
                    .isInstanceOf(SQLException.class)
                    .hasFieldOrPropertyWithValue("SQLState", "23514");
        }
    }

    @Test
    @DisplayName("reversal shape: reversal_of is set IFF the entry is a REVERSAL — both violations rejected")
    void reversal_shape_violations_are_rejected_by_check() throws SQLException {
        try (Connection migrate = migrateConnection()) {
            UUID originalId = insertEntry(migrate, "JOURNAL", null, marker("creator"), null);
            // A non-reversal claiming a target ...
            try (PreparedStatement insert = migrate.prepareStatement("""
                    INSERT INTO journal_entry (id, entry_type, description, reversal_of,
                                               idempotency_key, created_by, posted_at)
                    VALUES (?, 'JOURNAL', NULL, ?, NULL, ?, now())
                    """)) {
                insert.setObject(1, UUID.randomUUID());
                insert.setObject(2, originalId);
                insert.setString(3, marker("creator"));
                assertThatThrownBy(insert::executeUpdate)
                        .isInstanceOf(SQLException.class)
                        .hasFieldOrPropertyWithValue("SQLState", "23514");
            }
            // ... and a REVERSAL without one.
            try (PreparedStatement insert = migrate.prepareStatement("""
                    INSERT INTO journal_entry (id, entry_type, description, reversal_of,
                                               idempotency_key, created_by, posted_at)
                    VALUES (?, 'REVERSAL', NULL, NULL, NULL, ?, now())
                    """)) {
                insert.setObject(1, UUID.randomUUID());
                insert.setString(2, marker("creator"));
                assertThatThrownBy(insert::executeUpdate)
                        .isInstanceOf(SQLException.class)
                        .hasFieldOrPropertyWithValue("SQLState", "23514");
            }
        }
    }

    @Test
    @DisplayName("posting currency must be exactly three uppercase letters — same shape rule as account.currency")
    void malformed_posting_currency_is_rejected_by_check() throws SQLException {
        try (Connection migrate = migrateConnection()) {
            UUID accountId = insertAccountWithBalance(migrate);
            UUID entryId = insertEntry(migrate, "JOURNAL", null, marker("creator"), null);
            try (PreparedStatement insert = migrate.prepareStatement("""
                    INSERT INTO posting (id, entry_id, account_id, amount, currency, posted_at)
                    VALUES (?, ?, ?, 100, 'usd', now())
                    """)) {
                insert.setObject(1, UUID.randomUUID());
                insert.setObject(2, entryId);
                insert.setObject(3, accountId);
                assertThatThrownBy(insert::executeUpdate)
                        .isInstanceOf(SQLException.class)
                        .hasFieldOrPropertyWithValue("SQLState", "23514");
            }
        }
    }

    @Test
    @DisplayName("account_balance rejects a negative posting_count — the reconciliation watermark only moves forward")
    void negative_posting_count_is_rejected_by_check() throws SQLException {
        try (Connection migrate = migrateConnection()) {
            // A bare account (no balance row yet): the probe must hit the CHECK (23514), not the
            // primary key (23505), so the balance row for this account is inserted only after.
            UUID accountId = insertAccount(migrate);
            try (PreparedStatement insert = migrate.prepareStatement("""
                    INSERT INTO account_balance (account_id, balance, posting_count, updated_at)
                    VALUES (?, 0, -1, now())
                    """)) {
                insert.setObject(1, accountId);
                assertThatThrownBy(insert::executeUpdate)
                        .isInstanceOf(SQLException.class)
                        .hasFieldOrPropertyWithValue("SQLState", "23514");
            }
            // Restore the global precondition asserted below: no account may outlive a test
            // without its balance row.
            try (PreparedStatement insert = migrate.prepareStatement("""
                    INSERT INTO account_balance (account_id, balance, posting_count, updated_at)
                    VALUES (?, 0, 0, now())
                    """)) {
                insert.setObject(1, accountId);
                assertThat(insert.executeUpdate()).isEqualTo(1);
            }
        }
    }

    @Test
    @DisplayName("I11: an entry can be reversed at most once — a second reversal of the same entry is rejected")
    void second_reversal_of_the_same_entry_is_rejected() throws SQLException {
        try (Connection migrate = migrateConnection()) {
            UUID originalId = insertEntry(migrate, "JOURNAL", null, marker("creator"), null);
            insertEntry(migrate, "REVERSAL", originalId, marker("creator"), null);
            try (PreparedStatement second = migrate.prepareStatement("""
                    INSERT INTO journal_entry (id, entry_type, description, reversal_of,
                                               idempotency_key, created_by, posted_at)
                    VALUES (?, 'REVERSAL', NULL, ?, NULL, ?, now())
                    """)) {
                second.setObject(1, UUID.randomUUID());
                second.setObject(2, originalId);
                second.setString(3, marker("creator"));
                assertThatThrownBy(second::executeUpdate)
                        .isInstanceOf(SQLException.class)
                        .hasFieldOrPropertyWithValue("SQLState", "23505"); // unique_violation
            }
        }
    }

    @Test
    @DisplayName("ADR-0004: a duplicate (created_by, idempotency_key) is rejected by the permanent backstop index")
    void duplicate_idempotency_key_for_the_same_creator_is_rejected() throws SQLException {
        try (Connection migrate = migrateConnection()) {
            String creator = marker("creator");
            String key = marker("key");
            insertEntry(migrate, "JOURNAL", null, creator, key);
            try (PreparedStatement second = migrate.prepareStatement("""
                    INSERT INTO journal_entry (id, entry_type, description, reversal_of,
                                               idempotency_key, created_by, posted_at)
                    VALUES (?, 'JOURNAL', NULL, NULL, ?, ?, now())
                    """)) {
                second.setObject(1, UUID.randomUUID());
                second.setString(2, key);
                second.setString(3, creator);
                assertThatThrownBy(second::executeUpdate)
                        .isInstanceOf(SQLException.class)
                        .hasFieldOrPropertyWithValue("SQLState", "23505");
            }
        }
    }

    @Test
    @DisplayName("NULL idempotency keys are exempt from the backstop — entries without a key are never deduplicated")
    void entries_without_idempotency_keys_are_exempt_from_the_backstop() throws SQLException {
        // The index is partial (WHERE idempotency_key IS NOT NULL): keyless entries from the same
        // creator must coexist freely, or every non-idempotent post after the first would 23505.
        try (Connection migrate = migrateConnection()) {
            String creator = marker("creator");
            insertEntry(migrate, "JOURNAL", null, creator, null);
            insertEntry(migrate, "JOURNAL", null, creator, null); // helper asserts the insert succeeded
        }
    }

    @Test
    @DisplayName("every account has a balance row — the ADR-0003 lock protocol's global precondition")
    void every_account_has_a_balance_row() throws SQLException {
        // Deliberately quantified over ALL rows — the one sanctioned exception to the
        // additive-safe discipline: the invariant is global BY CONSTRUCTION (V3's backfill covers
        // M1-era accounts, the M2 create-account transaction covers new ones, and every raw
        // fixture in this suite inserts its snapshot row by hand), so the global assertion is
        // exactly the proof wanted — any writer anywhere that creates an account without its
        // balance row has already broken the lock protocol and must fail loudly here. In this
        // harness Flyway runs V3 against an empty account table (migrations precede all test
        // rows), so the backfill's effect on genuinely pre-existing accounts is proven by the
        // migration's own completeness DO block, which runs in every environment.
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet orphans = statement.executeQuery("""
                     SELECT count(*) AS orphans
                     FROM account a
                     LEFT JOIN account_balance b ON b.account_id = a.id
                     WHERE b.account_id IS NULL
                     """)) {
            assertThat(orphans.next()).isTrue();
            assertThat(orphans.getLong("orphans")).as("accounts without a balance row").isZero();
        }
    }

    /**
     * Table-owner connection. CHECK and unique-index probes run as {@code ledger_migrate}
     * deliberately: the constraints are defense in depth against non-API writes (V2's rationale),
     * so they must stop even the most privileged writer — proving them against {@code ledger_app}
     * alone would prove too little.
     */
    private Connection migrateConnection() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), "ledger_migrate", "ledger_migrate");
    }

    /**
     * The ACL as the application pool sees it. Exact set, not absence-of-DELETE: privilege creep
     * (DELETE, TRUNCATE, TRIGGER, REFERENCES) must fail the assertion, mirroring V3's own
     * self-verification DO blocks.
     */
    private Set<String> runtimeRoleGrantsOn(String table) throws SQLException {
        Set<String> granted = new HashSet<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement acl = connection.prepareStatement("""
                     SELECT a.privilege_type
                     FROM pg_class c, aclexplode(c.relacl) a
                     WHERE c.oid = ?::regclass
                       AND a.grantee = 'ledger_app'::regrole
                     """)) {
            acl.setString(1, "public." + table);
            try (ResultSet rs = acl.executeQuery()) {
                while (rs.next()) {
                    granted.add(rs.getString("privilege_type"));
                }
            }
        }
        return granted;
    }

    /** Marker-named fixture value (shared-schema discipline). */
    private static String marker(String label) {
        return "journal-schema-probe-" + label + "-" + UUID.randomUUID();
    }

    private static UUID insertAccount(Connection connection) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO account (id, name, currency, type, status, allow_negative,
                                     version, created_at, updated_at)
                VALUES (?, ?, 'USD', 'ASSET', 'ACTIVE', false, 0, now(), now())
                """)) {
            insert.setObject(1, id);
            insert.setString(2, "journal-schema-probe-" + id);
            assertThat(insert.executeUpdate()).isEqualTo(1);
        }
        return id;
    }

    private static UUID insertAccountWithBalance(Connection connection) throws SQLException {
        UUID id = insertAccount(connection);
        // Raw fixtures honor by hand what the M2 create-account transaction honors in code:
        // every account gets its snapshot row (V2 forward-contract), or the global assertion
        // in every_account_has_a_balance_row would rightly convict this test.
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO account_balance (account_id, balance, posting_count, updated_at)
                VALUES (?, 0, 0, now())
                """)) {
            insert.setObject(1, id);
            assertThat(insert.executeUpdate()).isEqualTo(1);
        }
        return id;
    }

    private static UUID insertEntry(Connection connection, String entryType, UUID reversalOf,
            String createdBy, String idempotencyKey) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO journal_entry (id, entry_type, description, reversal_of,
                                           idempotency_key, created_by, posted_at)
                VALUES (?, ?, NULL, ?, ?, ?, now())
                """)) {
            insert.setObject(1, id);
            insert.setString(2, entryType);
            insert.setObject(3, reversalOf);
            insert.setString(4, idempotencyKey);
            insert.setString(5, createdBy);
            assertThat(insert.executeUpdate()).isEqualTo(1);
        }
        return id;
    }
}
