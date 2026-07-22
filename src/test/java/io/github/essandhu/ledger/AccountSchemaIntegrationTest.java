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
 * I16 (M1): the V2 account migration applies cleanly and establishes least privilege for the
 * runtime role. Second prong of the two-pronged M0 pattern: V2 self-verifies its ACL in every
 * environment; this test independently re-proves it through the application's own pool, plus
 * behavioral probes — a positive one (the grants the status API needs actually work) and a
 * negative one (DELETE is denied by the database, not by code).
 */
@LedgerIntegrationTest
@DisplayName("I16: V2 account migration and grants")
class AccountSchemaIntegrationTest {

    /** The application pool — connects as the runtime role {@code ledger_app}. */
    @Autowired
    private DataSource dataSource;

    @Autowired
    private PostgreSQLContainer postgres;

    @Test
    @DisplayName("I16: V2 is recorded as applied in flyway history")
    void v2_account_migration_is_recorded_in_flyway_history() throws SQLException {
        // flyway_schema_history is owned by ledger_migrate and not granted to the runtime role.
        try (Connection migrate = DriverManager.getConnection(
                     postgres.getJdbcUrl(), "ledger_migrate", "ledger_migrate");
             Statement statement = migrate.createStatement();
             ResultSet history = statement.executeQuery(
                     "SELECT success FROM flyway_schema_history WHERE version = '2'")) {
            assertThat(history.next()).as("V2 migration recorded").isTrue();
            assertThat(history.getBoolean("success")).isTrue();
        }
    }

    @Test
    @DisplayName("I16: ledger_app's grants on account are EXACTLY {SELECT, INSERT, UPDATE}")
    void runtime_role_grants_on_account_are_exactly_select_insert_update() throws SQLException {
        // Exact set, not DELETE-absence: privilege creep (DELETE, TRUNCATE, TRIGGER, REFERENCES)
        // must fail this test, mirroring V2's own self-verification DO block.
        Set<String> granted = new HashSet<>();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet acl = statement.executeQuery("""
                     SELECT a.privilege_type
                     FROM pg_class c, aclexplode(c.relacl) a
                     WHERE c.oid = 'public.account'::regclass
                       AND a.grantee = 'ledger_app'::regrole
                     """)) {
            while (acl.next()) {
                granted.add(acl.getString("privilege_type"));
            }
        }
        assertThat(granted).containsExactlyInAnyOrder("SELECT", "INSERT", "UPDATE");
    }

    @Test
    @DisplayName("I16: the runtime role cannot DELETE accounts — denied by the database, not by code")
    void runtime_role_cannot_delete_from_account() throws SQLException {
        // The privilege check fires before row matching, so the probe is additive-safe: it fails
        // with 42501 whether or not the random id exists. If the grant were wrongly present, the
        // statement would succeed against zero rows — and the absence of an exception fails the test.
        try (Connection connection = dataSource.getConnection();
             PreparedStatement delete = connection.prepareStatement(
                     "DELETE FROM account WHERE id = ?")) {
            delete.setObject(1, UUID.randomUUID());
            assertThatThrownBy(delete::executeUpdate)
                    .isInstanceOf(SQLException.class)
                    .hasFieldOrPropertyWithValue("SQLState", "42501"); // insufficient_privilege
        }
    }

    @Test
    @DisplayName("I16: the runtime role can INSERT and UPDATE accounts (the grants the API needs)")
    void runtime_role_can_insert_and_update_account() throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO account (id, name, currency, type, status, allow_negative,
                                         version, created_at, updated_at)
                    VALUES (?, ?, 'USD', 'ASSET', 'ACTIVE', false, 0, now(), now())
                    """)) {
                insert.setObject(1, id);
                insert.setString(2, "schema-probe-" + id);
                assertThat(insert.executeUpdate()).isEqualTo(1);
            }
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE account SET name = ?, updated_at = now() WHERE id = ?")) {
                update.setString(1, "schema-probe-renamed-" + id);
                update.setObject(2, id);
                assertThat(update.executeUpdate()).isEqualTo(1);
            }
        }
    }
}
