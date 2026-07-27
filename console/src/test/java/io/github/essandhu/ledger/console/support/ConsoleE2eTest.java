package io.github.essandhu.ledger.console.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.Tag;

/**
 * The one way to write a console e2e test — the browser lane (M8c, ADR-0007). Deliberately
 * carries NO Spring annotation: the console under test is a SEPARATE process, reached over
 * HTTP through a real browser, so there is no application context to build here and nothing to
 * stub. Everything the other console tests fake — Keycloak, the ledger API, the token relay —
 * is real in this lane, which is exactly what it exists to prove.
 *
 * <p>The tag routes the class: {@code :console:e2eTest} includes it and {@code :console:test}
 * excludes it. That exclusion is load-bearing, not tidiness — the required "Console build" job
 * runs {@code :console:build}, which has no compose stack, no console on 8090, and no browser
 * binaries. This lane is the ONE verification task not wired into {@code check}.
 *
 * <p>Preconditions, all external: {@code docker compose up -d --wait} (PostgreSQL + Keycloak +
 * the API), then the console itself on {@code localhost:8090} — the host-run phase of
 * ADR-0007's issuer topology, because Boot's OAuth2 <em>client</em> performs eager issuer
 * discovery at startup and an in-container {@code localhost:8081} would be the container's own
 * loopback. Missing preconditions fail loudly and immediately (see the base-URL probe).
 *
 * <p>Plain {@code @BeforeAll}/{@code @AfterAll} lifecycle, never Playwright's
 * {@code @UsePlaywright} extension: that extension is experimental and upstream-tested only on
 * Jupiter 5.14, and this stack is JUnit Platform 6 — the exact shape of compatibility claim
 * ADR-0005's casualty history says to verify rather than assume.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@Tag("e2e")
public @interface ConsoleE2eTest {
}
