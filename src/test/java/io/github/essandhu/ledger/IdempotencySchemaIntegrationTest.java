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
 * I16 (M4): the V4 idempotency migration applies cleanly and establishes ADR-0004's bookkeeping
 * contract at the database — the (created_by, idem_key) primary key that arbitrates races, the
 * shape CHECKs (key rules, hash hex, response body IS JSON) proven against the table OWNER
 * (defense in depth against non-API writes, the V2/V3 rationale), the journal_entry key CHECKs
 * V4 adds, and the grant set: {SELECT, INSERT, DELETE} — the schema's first DELETE grant, held
 * for the designed-but-disabled purge, with UPDATE still denied (records are written once).
 * V3's backstop index needs no re-proof here ({@link JournalSchemaIntegrationTest} owns it).
 */
@LedgerIntegrationTest
@DisplayName("I16: V4 idempotency migration, grants, and constraints")
class IdempotencySchemaIntegrationTest {

    /** The application pool — connects as the runtime role {@code ledger_app}. */
    @Autowired
    private DataSource dataSource;

    @Autowired
    private PostgreSQLContainer postgres;

    @Test
    @DisplayName("I16: V4 is recorded as applied in flyway history")
    void v4_idempotency_migration_is_recorded_in_flyway_history() throws SQLException {
        try (Connection migrate = migrateConnection();
             Statement statement = migrate.createStatement();
             ResultSet history = statement.executeQuery(
                     "SELECT success FROM flyway_schema_history WHERE version = '4'")) {
            assertThat(history.next()).as("V4 migration recorded").isTrue();
            assertThat(history.getBoolean("success")).isTrue();
        }
    }

    @Test
    @DisplayName("ADR-0004: ledger_app's grants on idempotency_record are EXACTLY {SELECT, INSERT, DELETE} — the purge path is granted, rewriting is not")
    void runtime_role_grants_are_exactly_select_insert_delete() throws SQLException {
        assertThat(runtimeRoleGrantsOn("idempotency_record"))
                .containsExactlyInAnyOrder("SELECT", "INSERT", "DELETE");
    }

    @Test
    @DisplayName("the (created_by, idem_key) scope is the primary key — a duplicate insert is the 23505 that arbitrates races")
    void duplicate_scope_violates_the_primary_key() throws SQLException {
        try (Connection migrate = migrateConnection()) {
            UUID entryId = insertKeylessEntry(migrate);
            String createdBy = marker("dup-creator");
            insertRecord(migrate, createdBy, "the-key", entryId);
            assertThatThrownBy(() -> insertRecord(migrate, createdBy, "the-key", entryId))
                    .isInstanceOf(SQLException.class)
                    .hasFieldOrPropertyWithValue("SQLState", "23505"); // unique_violation
        }
    }

    @Test
    @DisplayName("key shape CHECKs stop even the table owner: blank, control characters, commas, over 200 chars")
    void key_shape_checks_reject_bad_keys() throws SQLException {
        try (Connection migrate = migrateConnection()) {
            UUID entryId = insertKeylessEntry(migrate);
            for (String badKey : new String[] {"   ", "tab\there", "K,K", "x".repeat(201)}) {
                assertThatThrownBy(() -> insertRecord(migrate, marker("shape"), badKey, entryId))
                        .as("idem_key %s".formatted(badKey.replace("\t", "\\t")))
                        .isInstanceOf(SQLException.class)
                        .hasFieldOrPropertyWithValue("SQLState", "23514"); // check_violation
            }
        }
    }

    @Test
    @DisplayName("request_hash must be 64 lowercase hex chars; response_body must be a JSON OBJECT — the only shape the writer stores")
    void hash_and_body_shape_checks_hold() throws SQLException {
        try (Connection migrate = migrateConnection()) {
            UUID entryId = insertKeylessEntry(migrate);
            assertThatThrownBy(() -> insertRecord(migrate, marker("hash"), "k", entryId,
                    "NOT-A-HASH", "{}"))
                    .isInstanceOf(SQLException.class)
                    .hasFieldOrPropertyWithValue("SQLState", "23514");
            // IS JSON OBJECT: non-JSON and valid-but-non-object documents (a bare scalar,
            // an array) are both refused — the replay path serves this column verbatim as a
            // 200 body, so only what the writer actually stores may exist at rest.
            for (String badBody : new String[] {"not json at all", "42", "[1,2]"}) {
                assertThatThrownBy(() -> insertRecord(migrate, marker("body"), "k", entryId,
                        "a".repeat(64), badBody))
                        .as("response_body %s".formatted(badBody))
                        .isInstanceOf(SQLException.class)
                        .hasFieldOrPropertyWithValue("SQLState", "23514");
            }
        }
    }

    @Test
    @DisplayName("entry_id is a real FK: a record cannot point at an entry that never existed")
    void record_requires_an_existing_entry() throws SQLException {
        try (Connection migrate = migrateConnection()) {
            assertThatThrownBy(() ->
                    insertRecord(migrate, marker("fk"), "k", UUID.randomUUID()))
                    .isInstanceOf(SQLException.class)
                    .hasFieldOrPropertyWithValue("SQLState", "23503"); // foreign_key_violation
        }
    }

    @Test
    @DisplayName("records are written once: UPDATE is denied to ledger_app (42501), while the granted DELETE works")
    void app_role_cannot_update_but_can_delete() throws SQLException {
        String createdBy = marker("upd-creator");
        try (Connection migrate = migrateConnection()) {
            UUID entryId = insertKeylessEntry(migrate);
            insertRecord(migrate, createdBy, "the-key", entryId);
        }
        try (Connection app = dataSource.getConnection()) {
            try (PreparedStatement update = app.prepareStatement(
                    "UPDATE idempotency_record SET response_status = 500 WHERE created_by = ?")) {
                update.setString(1, createdBy);
                assertThatThrownBy(update::executeUpdate)
                        .isInstanceOf(SQLException.class)
                        .hasFieldOrPropertyWithValue("SQLState", "42501"); // insufficient_privilege
            }
            // The DELETE grant is real — the purge needs no escalation (ADR-0004: enabling it
            // is configuration, not migration). Deleting only this test's own marker row.
            try (PreparedStatement delete = app.prepareStatement(
                    "DELETE FROM idempotency_record WHERE created_by = ?")) {
                delete.setString(1, createdBy);
                assertThat(delete.executeUpdate()).isEqualTo(1);
            }
        }
    }

    @Test
    @DisplayName("V4 arms journal_entry's key CHECKs: a blank, comma-bearing, or oversized idempotency_key is rejected at rest; NULL (pre-M4 rows) still passes")
    void journal_entry_key_checks_hold() throws SQLException {
        try (Connection migrate = migrateConnection()) {
            for (String badKey : new String[] {" ", "a,b", "x".repeat(201)}) {
                assertThatThrownBy(() -> insertEntry(migrate, badKey))
                        .as("idempotency_key '%s...'".formatted(
                                badKey.substring(0, Math.min(5, badKey.length()))))
                        .isInstanceOf(SQLException.class)
                        .hasFieldOrPropertyWithValue("SQLState", "23514");
            }
            // NULL passes by three-valued logic — every pre-M4 row keeps loading.
            insertKeylessEntry(migrate);
        }
    }

    /** Table-owner connection: constraint probes must stop even the most privileged writer. */
    private Connection migrateConnection() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), "ledger_migrate", "ledger_migrate");
    }

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
        return "idem-schema-probe-" + label + "-" + UUID.randomUUID();
    }

    private static UUID insertKeylessEntry(Connection connection) throws SQLException {
        return insertEntry(connection, null);
    }

    private static UUID insertEntry(Connection connection, String idempotencyKey)
            throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO journal_entry (id, entry_type, description, reversal_of,
                                           idempotency_key, created_by, posted_at)
                VALUES (?, 'JOURNAL', NULL, NULL, ?, ?, now())
                """)) {
            insert.setObject(1, id);
            insert.setString(2, idempotencyKey);
            insert.setString(3, marker("creator"));
            assertThat(insert.executeUpdate()).isEqualTo(1);
        }
        return id;
    }

    private static void insertRecord(Connection connection, String createdBy, String idemKey,
            UUID entryId) throws SQLException {
        insertRecord(connection, createdBy, idemKey, entryId, "a".repeat(64), "{\"ok\":true}");
    }

    private static void insertRecord(Connection connection, String createdBy, String idemKey,
            UUID entryId, String requestHash, String responseBody) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO idempotency_record (created_by, idem_key, request_hash, entry_id,
                                                response_status, response_body,
                                                created_at, expires_at)
                VALUES (?, ?, ?, ?, 201, ?, now(), now() + interval '90 days')
                """)) {
            insert.setString(1, createdBy);
            insert.setString(2, idemKey);
            insert.setString(3, requestHash);
            insert.setObject(4, entryId);
            insert.setString(5, responseBody);
            assertThat(insert.executeUpdate()).isEqualTo(1);
        }
    }
}
