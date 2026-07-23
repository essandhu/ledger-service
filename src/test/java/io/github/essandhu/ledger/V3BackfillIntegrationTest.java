package io.github.essandhu.ledger;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The V2 forward-contract's REAL proof (sweep item 1): V3's backfill must give a genuinely
 * pre-V3 account its zero snapshot row. The shared {@code @LedgerIntegrationTest} harness can
 * never exercise this — Flyway migrates V1→latest before any test row exists, so the backfill
 * {@code INSERT … SELECT} always runs over an EMPTY account table there (a gap
 * JournalSchemaIntegrationTest documents and defers to the migration's own DO block). This
 * class closes the gap by replaying M1 history: programmatic Flyway to target 2, an account
 * inserted while {@code account_balance} does not yet exist, then migrate to latest and read
 * the backfilled row.
 *
 * <p>Deliberately NOT {@code @LedgerIntegrationTest}, and {@code @Tag("integration")} applied
 * directly — the one sanctioned exception to the composed-annotation rule: no Spring context
 * is wanted (booting the app would migrate straight to latest, destroying the between-V2-and-V3
 * state this test exists to create), and a private container is the point, not an accident —
 * the shared container/schema stays untouched. Plain JUnit + Testcontainers + the Flyway API
 * (on the classpath via spring-boot-flyway), mirroring PostgresContainerConfig's bootstrap
 * (same image, same {@code bootstrap-roles.sql} two-role model) so the migration runs exactly
 * as it does everywhere else: as {@code ledger_migrate}, never the runtime role.
 */
@Tag("integration")
@DisplayName("I16: V3 backfills the zero snapshot for a genuinely M1-era account")
class V3BackfillIntegrationTest {

    /** The M1-era account's birth instant — fixed, like every instant in this suite. */
    private static final Instant M1_CREATED_AT = Instant.parse("2026-07-22T10:00:00Z");

    @Test
    @DisplayName("an account created between V2 and V3 gets balance 0, posting_count 0, updated_at = its created_at")
    void v3_backfills_the_m1_era_account() throws SQLException {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4-alpine")
                .withDatabaseName("ledger")
                .withUsername("postgres")
                .withPassword("postgres")
                .withInitScript("db/testsupport/bootstrap-roles.sql")) {
            postgres.start();

            migrate(postgres, "2");
            assertThat(tableExists(postgres, "account_balance"))
                    .as("the probe account must GENUINELY predate V3 — no snapshot table yet")
                    .isFalse();
            UUID accountId = UUID.randomUUID();
            insertM1EraAccount(postgres, accountId);

            migrate(postgres, "latest");

            try (Connection connection = migrateConnection(postgres);
                 PreparedStatement select = connection.prepareStatement("""
                         SELECT balance, posting_count, updated_at
                         FROM account_balance
                         WHERE account_id = ?
                         """)) {
                select.setObject(1, accountId);
                try (ResultSet row = select.executeQuery()) {
                    assertThat(row.next()).as("backfilled snapshot row exists").isTrue();
                    assertThat(row.getLong("balance"))
                            .as("zero balance is exact, not approximate: no posting predates the table")
                            .isZero();
                    assertThat(row.getLong("posting_count")).isZero();
                    assertThat(row.getObject("updated_at", OffsetDateTime.class).toInstant())
                            .as("updated_at mirrors the account's created_at — a migration must not read a clock")
                            .isEqualTo(M1_CREATED_AT);
                }
            }
        }
    }

    /** Flyway as the schema-owning role — exactly how V1..V3 run in every environment (I16). */
    private static void migrate(PostgreSQLContainer postgres, String target) {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), "ledger_migrate", "ledger_migrate")
                .target(target)
                .load()
                .migrate();
    }

    /**
     * The M1-era row, raw JDBC as the migrate role, satisfying every V2 CHECK — the exact
     * state an account created through the M1 API occupied before V3 existed.
     */
    private static void insertM1EraAccount(PostgreSQLContainer postgres, UUID accountId)
            throws SQLException {
        try (Connection connection = migrateConnection(postgres);
             PreparedStatement insert = connection.prepareStatement("""
                     INSERT INTO account
                         (id, name, currency, type, status, allow_negative, version, created_at, updated_at)
                     VALUES (?, ?, 'EUR', 'ASSET', 'ACTIVE', false, 0, ?, ?)
                     """)) {
            insert.setObject(1, accountId);
            insert.setString(2, "v3-backfill-probe-" + accountId);
            insert.setObject(3, OffsetDateTime.ofInstant(M1_CREATED_AT, ZoneOffset.UTC));
            insert.setObject(4, OffsetDateTime.ofInstant(M1_CREATED_AT, ZoneOffset.UTC));
            assertThat(insert.executeUpdate()).isEqualTo(1);
        }
    }

    private static boolean tableExists(PostgreSQLContainer postgres, String table)
            throws SQLException {
        try (Connection connection = migrateConnection(postgres);
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery(
                     "SELECT to_regclass('public.%s') IS NOT NULL".formatted(table))) {
            assertThat(row.next()).isTrue();
            return row.getBoolean(1);
        }
    }

    private static Connection migrateConnection(PostgreSQLContainer postgres) throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), "ledger_migrate", "ledger_migrate");
    }
}
