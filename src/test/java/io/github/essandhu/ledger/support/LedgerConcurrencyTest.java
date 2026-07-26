package io.github.essandhu.ledger.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.Tag;

/**
 * The one way to write an M5 concurrency test: exactly {@link LedgerIntegrationTest}'s
 * annotation set — same merged context configuration, so the concurrency classes share the one
 * cached context and one PostgreSQL container with every other integration class in a JVM
 * (extra JUnit tags do not change the Spring context key) — plus the {@code concurrency} tag
 * that moves the class out of the default {@code test} lane and into the {@code concurrencyTest}
 * Gradle task (TEST-STRATEGY §2: the first slow suite gets its own tag-filtered lane at M5).
 *
 * <p>Classes carry BOTH tags, honestly: these are integration tests (real HTTP surface, real
 * PostgreSQL) that happen to need a slower, parallel-writer lane. The default lane's
 * {@code excludeTags("concurrency")} wins over the inherited {@code integration} tag.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@LedgerIntegrationTest
@Tag("concurrency")
public @interface LedgerConcurrencyTest {
}
