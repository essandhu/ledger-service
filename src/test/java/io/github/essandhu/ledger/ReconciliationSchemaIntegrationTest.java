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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.postgresql.PostgreSQLContainer;

import io.github.essandhu.ledger.support.LedgerIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * I16 (M6): the V5 reconciliation and V6 Batch-metadata migrations apply cleanly and establish
 * their grant models. Second prong of the two-pronged pattern (V5/V6 self-verify their ACLs in
 * every environment; this class independently re-proves them through the application's own
 * pool) plus the CHECK probes for every database-level rule the run/finding records rely on as
 * defense in depth — exercised as the table OWNER, because a constraint that only stops
 * {@code ledger_app} proves too little (the V2 rationale: a row a DBA sneaks in can never be
 * one the domain refuses to load).
 */
@LedgerIntegrationTest
@DisplayName("I16: V5 reconciliation + V6 batch metadata migrations, grants, and constraints")
class ReconciliationSchemaIntegrationTest {

    /** The application pool — connects as the runtime role {@code ledger_app}. */
    @Autowired
    private DataSource dataSource;

    @Autowired
    private PostgreSQLContainer postgres;

    @Test
    @DisplayName("I16: V5 and V6 are recorded as applied in flyway history")
    void v5_and_v6_migrations_are_recorded_in_flyway_history() throws SQLException {
        try (Connection migrate = migrateConnection();
             Statement statement = migrate.createStatement();
             ResultSet history = statement.executeQuery(
                     "SELECT version, success FROM flyway_schema_history WHERE version IN ('5', '6')")) {
            Set<String> applied = new HashSet<>();
            while (history.next()) {
                assertThat(history.getBoolean("success")).isTrue();
                applied.add(history.getString("version"));
            }
            assertThat(applied).containsExactlyInAnyOrder("5", "6");
        }
    }

    @Test
    @DisplayName("I16: ledger_app's grants on reconciliation_run are EXACTLY {SELECT, INSERT, UPDATE} — runs finish, never vanish")
    void runtime_role_grants_on_reconciliation_run_are_exactly_select_insert_update()
            throws SQLException {
        assertThat(runtimeRoleGrantsOn("reconciliation_run"))
                .containsExactlyInAnyOrder("SELECT", "INSERT", "UPDATE");
    }

    @Test
    @DisplayName("I16: ledger_app's grants on reconciliation_finding are EXACTLY {SELECT, INSERT} — findings are audit history")
    void runtime_role_grants_on_reconciliation_finding_are_exactly_select_insert()
            throws SQLException {
        assertThat(runtimeRoleGrantsOn("reconciliation_finding"))
                .containsExactlyInAnyOrder("SELECT", "INSERT");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"batch_job_instance", "batch_job_execution",
            "batch_job_execution_params", "batch_step_execution", "batch_step_execution_context",
            "batch_job_execution_context"})
    @DisplayName("I16: ledger_app's grants on each BATCH_* table are EXACTLY {SELECT, INSERT, UPDATE}")
    void runtime_role_grants_on_batch_tables_are_exactly_select_insert_update(String table)
            throws SQLException {
        assertThat(runtimeRoleGrantsOn(table))
                .containsExactlyInAnyOrder("SELECT", "INSERT", "UPDATE");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"batch_job_instance_seq", "batch_job_execution_seq",
            "batch_step_execution_seq"})
    @DisplayName("I16: ledger_app's grants on each BATCH_* sequence are EXACTLY {USAGE}")
    void runtime_role_grants_on_batch_sequences_are_exactly_usage(String sequence)
            throws SQLException {
        assertThat(runtimeRoleGrantsOn(sequence)).containsExactlyInAnyOrder("USAGE");
    }

    @Test
    @DisplayName("reconciliation_run rejects statuses outside {RUNNING, CLEAN, DRIFT, FAILED}")
    void unknown_run_status_is_rejected_by_check() throws SQLException {
        try (Connection migrate = migrateConnection();
             PreparedStatement insert = migrate.prepareStatement("""
                     INSERT INTO reconciliation_run (id, started_at, finished_at, status, triggered_by)
                     VALUES (?, now(), now(), 'DONE', ?)
                     """)) {
            insert.setObject(1, UUID.randomUUID());
            insert.setString(2, marker("trigger"));
            assertThatThrownBy(insert::executeUpdate)
                    .isInstanceOf(SQLException.class)
                    .hasFieldOrPropertyWithValue("SQLState", "23514"); // check_violation
        }
    }

    @Test
    @DisplayName("run shape: RUNNING must be unfinished and unpopulated; a verdict must be finished and fully populated")
    void run_shape_checks_reject_inconsistent_rows() throws SQLException {
        try (Connection migrate = migrateConnection()) {
            // A RUNNING row with a finished_at violates finished_shape.
            expectCheckViolation(migrate, """
                    INSERT INTO reconciliation_run (id, started_at, finished_at, status, triggered_by)
                    VALUES (?, now(), now(), 'RUNNING', 'probe')
                    """);
            // A CLEAN row without result counts violates results_shape.
            expectCheckViolation(migrate, """
                    INSERT INTO reconciliation_run (id, started_at, finished_at, status, triggered_by)
                    VALUES (?, now(), now(), 'CLEAN', 'probe')
                    """);
            // A CLEAN row whose counts record drift violates verdict_matches_counts.
            expectCheckViolation(migrate, """
                    INSERT INTO reconciliation_run (id, started_at, finished_at, status, triggered_by,
                                                    accounts_checked, drift_count, currency_mismatch_count,
                                                    posted_at_mismatch_count, unbalanced_currency_count)
                    VALUES (?, now(), now(), 'CLEAN', 'probe', 1, 1, 0, 0, 0)
                    """);
            // A DRIFT row whose counts record nothing violates verdict_matches_counts too.
            expectCheckViolation(migrate, """
                    INSERT INTO reconciliation_run (id, started_at, finished_at, status, triggered_by,
                                                    accounts_checked, drift_count, currency_mismatch_count,
                                                    posted_at_mismatch_count, unbalanced_currency_count)
                    VALUES (?, now(), now(), 'DRIFT', 'probe', 1, 0, 0, 0, 0)
                    """);
        }
    }

    @Test
    @DisplayName("finding shape: the stored delta must be snapshot − computed, and a finding must record actual drift")
    void finding_shape_checks_reject_inconsistent_rows() throws SQLException {
        try (Connection migrate = migrateConnection()) {
            UUID accountId = insertAccountWithBalance(migrate);
            UUID runId = insertDriftRun(migrate);
            // Inconsistent delta violates delta_consistent.
            expectCheckViolation(migrate, """
                    INSERT INTO reconciliation_finding (id, run_id, account_id, snapshot_balance,
                                                        snapshot_count, computed_balance,
                                                        computed_count, delta)
                    VALUES (?, '%s', '%s', 100, 1, 90, 1, 5)
                    """.formatted(runId, accountId));
            // Identical pairs violate records_drift: a finding exists iff drift exists.
            expectCheckViolation(migrate, """
                    INSERT INTO reconciliation_finding (id, run_id, account_id, snapshot_balance,
                                                        snapshot_count, computed_balance,
                                                        computed_count, delta)
                    VALUES (?, '%s', '%s', 100, 1, 100, 1, 0)
                    """.formatted(runId, accountId));
        }
    }

    @Test
    @DisplayName("one finding per account per run — the (run_id, account_id) uniqueness holds")
    void duplicate_finding_for_same_account_and_run_is_rejected() throws SQLException {
        try (Connection migrate = migrateConnection()) {
            UUID accountId = insertAccountWithBalance(migrate);
            UUID runId = insertDriftRun(migrate);
            insertFinding(migrate, runId, accountId);
            try (PreparedStatement insert = migrate.prepareStatement("""
                    INSERT INTO reconciliation_finding (id, run_id, account_id, snapshot_balance,
                                                        snapshot_count, computed_balance,
                                                        computed_count, delta)
                    VALUES (?, ?, ?, 100, 1, 93, 1, 7)
                    """)) {
                insert.setObject(1, UUID.randomUUID());
                insert.setObject(2, runId);
                insert.setObject(3, accountId);
                assertThatThrownBy(insert::executeUpdate)
                        .isInstanceOf(SQLException.class)
                        .hasFieldOrPropertyWithValue("SQLState", "23505"); // unique_violation
            }
        }
    }

    @Test
    @DisplayName("findings are write-once and runs never vanish: UPDATE finding / DELETE either is denied through the app pool")
    void runtime_role_cannot_rewrite_findings_or_delete_history() throws SQLException {
        UUID accountId;
        UUID runId;
        try (Connection migrate = migrateConnection()) {
            accountId = insertAccountWithBalance(migrate);
            runId = insertDriftRun(migrate);
            insertFinding(migrate, runId, accountId);
        }
        try (Connection app = dataSource.getConnection()) {
            expectPrivilegeError(app,
                    "UPDATE reconciliation_finding SET delta = 0 WHERE run_id = '%s'".formatted(runId));
            expectPrivilegeError(app,
                    "DELETE FROM reconciliation_finding WHERE run_id = '%s'".formatted(runId));
            expectPrivilegeError(app,
                    "DELETE FROM reconciliation_run WHERE id = '%s'".formatted(runId));
        }
    }

    private static void expectCheckViolation(Connection connection, String sql)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(sql)) {
            insert.setObject(1, UUID.randomUUID());
            assertThatThrownBy(insert::executeUpdate)
                    .isInstanceOf(SQLException.class)
                    .hasFieldOrPropertyWithValue("SQLState", "23514"); // check_violation
        }
    }

    private static void expectPrivilegeError(Connection connection, String sql) {
        assertThatThrownBy(() -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
        }).isInstanceOf(SQLException.class)
                .hasFieldOrPropertyWithValue("SQLState", "42501"); // insufficient_privilege
    }

    /** A finished DRIFT run to hang finding probes on (drift_count 1 satisfies the verdict
     * CHECK; the probes themselves supply the finding — or deliberately fail to). */
    private static UUID insertDriftRun(Connection connection) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO reconciliation_run (id, started_at, finished_at, status, triggered_by,
                                                accounts_checked, drift_count, currency_mismatch_count,
                                                posted_at_mismatch_count, unbalanced_currency_count)
                VALUES (?, now(), now(), 'DRIFT', ?, 1, 1, 0, 0, 0)
                """)) {
            insert.setObject(1, id);
            insert.setString(2, marker("trigger"));
            assertThat(insert.executeUpdate()).isEqualTo(1);
        }
        return id;
    }

    private static void insertFinding(Connection connection, UUID runId, UUID accountId)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO reconciliation_finding (id, run_id, account_id, snapshot_balance,
                                                    snapshot_count, computed_balance,
                                                    computed_count, delta)
                VALUES (?, ?, ?, 100, 1, 93, 1, 7)
                """)) {
            insert.setObject(1, UUID.randomUUID());
            insert.setObject(2, runId);
            insert.setObject(3, accountId);
            assertThat(insert.executeUpdate()).isEqualTo(1);
        }
    }

    private static String marker(String label) {
        return "reconciliation-schema-probe-" + label + "-" + UUID.randomUUID();
    }

    private static UUID insertAccountWithBalance(Connection connection) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO account (id, name, currency, type, status, allow_negative,
                                     version, created_at, updated_at)
                VALUES (?, ?, 'USD', 'ASSET', 'ACTIVE', false, 0, now(), now())
                """)) {
            insert.setObject(1, id);
            insert.setString(2, "reconciliation-schema-probe-" + id);
            assertThat(insert.executeUpdate()).isEqualTo(1);
        }
        // Raw fixtures honor by hand what the M2 create-account transaction honors in code:
        // every account gets its snapshot row (V2 forward-contract), or the global assertion
        // in JournalSchemaIntegrationTest would rightly convict this test.
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO account_balance (account_id, balance, posting_count, updated_at)
                VALUES (?, 0, 0, now())
                """)) {
            insert.setObject(1, id);
            assertThat(insert.executeUpdate()).isEqualTo(1);
        }
        return id;
    }

    private Set<String> runtimeRoleGrantsOn(String relation) throws SQLException {
        Set<String> granted = new HashSet<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement acl = connection.prepareStatement("""
                     SELECT a.privilege_type
                     FROM pg_class c, aclexplode(c.relacl) a
                     WHERE c.oid = ?::regclass
                       AND a.grantee = 'ledger_app'::regrole
                     """)) {
            acl.setString(1, "public." + relation);
            try (ResultSet rs = acl.executeQuery()) {
                while (rs.next()) {
                    granted.add(rs.getString("privilege_type"));
                }
            }
        }
        return granted;
    }

    private Connection migrateConnection() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), "ledger_migrate", "ledger_migrate");
    }
}
