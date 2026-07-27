package io.github.essandhu.ledger.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Shared PostgreSQL 18.4 container for integration tests (no H2, ever).
 *
 * <p>Deliberately <em>not</em> {@code @ServiceConnection}: a service connection would hand Spring
 * the container's bootstrap superuser for both the application datasource and Flyway, collapsing
 * the two-role grant model these tests exist to prove. Instead the init script creates the same
 * roles as {@code docker/postgres/init}, only the JDBC URL is registered dynamically, and the
 * credentials come from {@code application.yaml} — so tests run with exactly the production
 * privilege split ({@code ledger_app} runtime, {@code ledger_migrate} for Flyway; invariants
 * I3 / I16). The container lifecycle is still owned by the Spring context, so one container is
 * shared per cached context.
 *
 * <p><b>M1 measurement + decision (the test-type rules' open question):</b> all integration
 * classes compose the identical annotation set via {@link LedgerIntegrationTest}, giving ONE
 * cached context and ONE container for the whole suite (verified: a single HikariPool /
 * EntityManagerFactory shutdown in the test log). Full {@code test} task incl. container
 * startup: ~29 s wall-clock (Windows 11 / Docker Desktop, 2026-07-22); 115 tests. Schema
 * isolation: none needed yet — classes share the schema and follow the additive-safe
 * discipline (rows carry per-test unique marker names; no assertion quantifies over rows the
 * test did not create — see AccountApiIntegrationTest). Revisit (per-class schemas or
 * clean-migrate) only when a class genuinely cannot be made additive-safe; that class, not the
 * suite, is the trigger.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresContainerConfig {

    @Bean
    PostgreSQLContainer ledgerPostgres() {
        return new PostgreSQLContainer("postgres:18.4-alpine")
                .withDatabaseName("ledger")
                .withUsername("postgres")
                .withPassword("postgres")
                .withInitScript("db/testsupport/bootstrap-roles.sql");
    }

    @Bean
    DynamicPropertyRegistrar ledgerDataSourceProperties(PostgreSQLContainer ledgerPostgres) {
        return registry -> registry.add("spring.datasource.url", ledgerPostgres::getJdbcUrl);
    }
}
