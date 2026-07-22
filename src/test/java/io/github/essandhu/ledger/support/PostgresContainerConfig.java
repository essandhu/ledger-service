package io.github.essandhu.ledger.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Shared PostgreSQL 18.4 container for integration tests (TEST-STRATEGY §1: no H2, ever).
 *
 * <p>Deliberately <em>not</em> {@code @ServiceConnection}: a service connection would hand Spring
 * the container's bootstrap superuser for both the application datasource and Flyway, collapsing
 * the two-role grant model these tests exist to prove. Instead the init script creates the same
 * roles as {@code docker/postgres/init}, only the JDBC URL is registered dynamically, and the
 * credentials come from {@code application.yaml} — so tests run with exactly the production
 * privilege split ({@code ledger_app} runtime, {@code ledger_migrate} for Flyway; invariants
 * I3 / I16). The container lifecycle is still owned by the Spring context, so one container is
 * shared per cached context (TEST-STRATEGY §2).
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
