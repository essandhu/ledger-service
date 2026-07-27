package io.github.essandhu.ledger.console;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Read-only web console for the ledger (ADR-0007): a separate Boot app that logs human users
 * in against Keycloak (authorization code) and renders server-side views over the ledger API.
 * The console is the API's first real consumer and is deliberately built as one — the core
 * stays a pure resource server; its security posture does not change at all.
 *
 * <p>Phase 1 runs on the HOST (port 8090, {@code ./gradlew :console:bootRun}): the plain
 * {@code issuer-uri} works for both the browser redirect and the JVM's eager startup
 * discovery, which a containerized console's own loopback would break (ADR-0007 records the
 * explicit-endpoints recipe for the containerized stretch).
 */
@SpringBootApplication
public class ConsoleApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConsoleApplication.class, args);
    }
}
