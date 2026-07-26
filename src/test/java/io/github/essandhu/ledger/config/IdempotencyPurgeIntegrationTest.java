package io.github.essandhu.ledger.config;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

import io.github.essandhu.ledger.application.service.IdempotencyPurgeService;
import io.github.essandhu.ledger.support.LedgerIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-0004 retention against real PostgreSQL: the batched ctid DELETE removes strictly-expired
 * records only, bounded per call, through the runtime role's granted DELETE — while the
 * scheduler that would drive it is ABSENT from the default context, because the purge ships
 * disabled ({@code ledger.idempotency.purge.enabled=false}). The journal entries the purged
 * records pointed at are untouched: purging degrades diagnostics, never history.
 */
@LedgerIntegrationTest
@DisplayName("ADR-0004 retention: batched expiry delete works; the purge scheduler ships disabled")
class IdempotencyPurgeIntegrationTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private IdempotencyPurgeService purge;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PostgreSQLContainer postgres;

    @Test
    @DisplayName("shipped disabled: no purge scheduler bean exists in the default context")
    void purge_scheduler_is_absent_by_default() {
        assertThat(context.getBeanNamesForType(IdempotencyPurger.class))
                .as("ledger.idempotency.purge.enabled defaults to false")
                .isEmpty();
    }

    @Test
    @DisplayName("purgeExpiredBatch deletes strictly-expired records, batch-bounded, sparing live rows and journal history")
    void purge_deletes_expired_records_in_bounded_batches() throws SQLException {
        String createdBy = "purge-int-" + UUID.randomUUID();
        UUID entryId;
        try (Connection migrate = migrateConnection()) {
            entryId = insertEntry(migrate, createdBy);
            insertRecord(migrate, createdBy, "expired-1", entryId, "- interval '2 days'");
            insertRecord(migrate, createdBy, "expired-2", entryId, "- interval '1 day'");
            insertRecord(migrate, createdBy, "expired-3", entryId, "- interval '1 hour'");
            insertRecord(migrate, createdBy, "live", entryId, "+ interval '90 days'");
        }

        // Batch bound honored while draining. The counts stay UNQUANTIFIED over other tests'
        // rows on purpose (shared-context discipline): the purge deletes any expired record
        // in the schema, so this asserts only the bound per call and that OUR rows reach
        // their pinned end state — a global 2/1/0 count would flake the moment another test
        // leaves an expired record behind.
        int drained;
        do {
            drained = purge.purgeExpiredBatch(2);
            assertThat(drained).as("the batch bound is a hard ceiling").isLessThanOrEqualTo(2);
        } while (drained > 0);

        assertThat(recordCount(createdBy)).as("the live record survives; the expired are gone")
                .isEqualTo(1);
        try (Connection app = dataSource.getConnection();
             PreparedStatement select = app.prepareStatement(
                     "SELECT count(*) FROM journal_entry WHERE id = ?")) {
            select.setObject(1, entryId);
            try (ResultSet row = select.executeQuery()) {
                assertThat(row.next()).isTrue();
                assertThat(row.getLong(1))
                        .as("purging records never touches journal history").isEqualTo(1);
            }
        }
    }

    private long recordCount(String createdBy) throws SQLException {
        try (Connection app = dataSource.getConnection();
             PreparedStatement select = app.prepareStatement(
                     "SELECT count(*) FROM idempotency_record WHERE created_by = ?")) {
            select.setString(1, createdBy);
            try (ResultSet row = select.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getLong(1);
            }
        }
    }

    private Connection migrateConnection() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), "ledger_migrate", "ledger_migrate");
    }

    private static UUID insertEntry(Connection connection, String createdBy) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO journal_entry (id, entry_type, description, reversal_of,
                                           idempotency_key, created_by, posted_at)
                VALUES (?, 'JOURNAL', NULL, NULL, NULL, ?, now())
                """)) {
            insert.setObject(1, id);
            insert.setString(2, createdBy);
            assertThat(insert.executeUpdate()).isEqualTo(1);
        }
        return id;
    }

    private static void insertRecord(Connection connection, String createdBy, String idemKey,
            UUID entryId, String expiryOffset) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO idempotency_record (created_by, idem_key, request_hash, entry_id,
                                                response_status, response_body,
                                                created_at, expires_at)
                VALUES (?, ?, ?, ?, 201, '{}', now() - interval '90 days', now() %s)
                """.formatted(expiryOffset))) {
            insert.setString(1, createdBy);
            insert.setString(2, idemKey);
            insert.setString(3, "a".repeat(64));
            insert.setObject(4, entryId);
            assertThat(insert.executeUpdate()).isEqualTo(1);
        }
    }
}
