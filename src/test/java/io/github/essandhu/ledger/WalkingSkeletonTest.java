package io.github.essandhu.ledger;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.testcontainers.postgresql.PostgreSQLContainer;

import io.github.essandhu.ledger.support.LedgerIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M0 walking skeleton: the full Spring context boots against real PostgreSQL, the Flyway
 * baseline establishes the two-role grant model (I16), and the security skeleton exposes
 * exactly one anonymous surface — {@code /actuator/health} (the seed of the I13 matrix).
 */
@LedgerIntegrationTest
@DisplayName("M0 walking skeleton")
class WalkingSkeletonTest {

    @Autowired
    private MockMvcTester mvc;

    /** The application pool — connects as the runtime role {@code ledger_app}. */
    @Autowired
    private DataSource dataSource;

    @Autowired
    private PostgreSQLContainer postgres;

    @Test
    @DisplayName("I16: Flyway baseline applies cleanly to an empty PostgreSQL 18")
    void flyway_baseline_applies_cleanly_from_an_empty_database() throws SQLException {
        // flyway_schema_history is owned by ledger_migrate and deliberately not granted to the
        // runtime role, so the assertion connects as the migration role.
        try (Connection migrate = DriverManager.getConnection(
                     postgres.getJdbcUrl(), "ledger_migrate", "ledger_migrate");
             Statement statement = migrate.createStatement();
             ResultSet history = statement.executeQuery(
                     "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank")) {
            assertThat(history.next()).as("baseline migration recorded").isTrue();
            assertThat(history.getString("version")).isEqualTo("1");
            assertThat(history.getBoolean("success")).isTrue();
        }
    }

    @Test
    @DisplayName("I16: the baseline's grants are in the schema ACL — not just PostgreSQL defaults")
    void runtime_role_grants_match_the_baseline() throws SQLException {
        // Deliberately inspects the ACL, not has_schema_privilege(): effective-privilege
        // functions cannot tell a direct grant from PUBLIC inheritance, and on PostgreSQL 15+
        // the stock defaults (USAGE via PUBLIC, CREATE denied) would satisfy them even if the
        // migration had silently granted nothing.
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet grants = statement.executeQuery("""
                     SELECT current_user,
                            EXISTS (SELECT 1
                                    FROM pg_namespace n, aclexplode(n.nspacl) a
                                    WHERE n.nspname = 'public'
                                      AND a.grantee = 'ledger_app'::regrole
                                      AND a.privilege_type = 'USAGE')  AS direct_usage,
                            NOT EXISTS (SELECT 1
                                        FROM pg_namespace n, aclexplode(n.nspacl) a
                                        WHERE n.nspname = 'public'
                                          AND a.grantee = 0  -- PUBLIC
                                          AND a.privilege_type = 'CREATE') AS public_create_revoked
                     """)) {
            assertThat(grants.next()).isTrue();
            assertThat(grants.getString("current_user")).isEqualTo("ledger_app");
            assertThat(grants.getBoolean("direct_usage"))
                    .as("direct USAGE grant for ledger_app in the ACL").isTrue();
            assertThat(grants.getBoolean("public_create_revoked"))
                    .as("no CREATE for PUBLIC in the ACL").isTrue();
        }
    }

    @Test
    @DisplayName("I3 (seed): the runtime role cannot create objects — denied by the database, not by code")
    void runtime_role_cannot_create_tables() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.execute("CREATE TABLE m0_privilege_probe (id int)"))
                    .isInstanceOf(SQLException.class)
                    .hasFieldOrPropertyWithValue("SQLState", "42501"); // insufficient_privilege
        }
    }

    @Test
    @DisplayName("I13 (seed): health endpoint (incl. probes) is the one anonymous surface, and it is UP")
    void health_is_anonymous_and_up() {
        assertThat(mvc.get().uri("/actuator/health")).hasStatusOk()
                .bodyJson().extractingPath("$.status").isEqualTo("UP");
        assertThat(mvc.get().uri("/actuator/health/liveness")).hasStatusOk();
        assertThat(mvc.get().uri("/actuator/health/readiness")).hasStatusOk();
    }

    @Test
    @DisplayName("I13 (seed): every surface except health requires authentication — 401 without a token")
    void everything_except_health_requires_a_token() {
        for (String path : List.of(
                "/api/v1/accounts",
                "/actuator/prometheus",
                "/actuator/metrics",
                "/actuator/info")) {
            assertThat(mvc.get().uri(path)).as(path).hasStatus(HttpStatus.UNAUTHORIZED);
        }
    }
}
