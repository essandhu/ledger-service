package io.github.essandhu.ledger.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

/**
 * The one way to write an integration test in this project. Composing the exact annotation set
 * matters, not just convenience: the Spring TestContext cache keys on the merged context
 * configuration, so any class that hand-rolls a slightly different set silently forks a second
 * context — and a second PostgreSQL container — invalidating the one-context/one-container
 * economics recorded in {@link PostgresContainerConfig} (TEST-STRATEGY §2).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresContainerConfig.class)
@Tag("integration")
public @interface LedgerIntegrationTest {
}
