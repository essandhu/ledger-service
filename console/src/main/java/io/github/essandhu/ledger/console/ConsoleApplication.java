package io.github.essandhu.ledger.console;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Read-only web console for the ledger (ADR-0007): a separate Boot app that logs human users
 * in against Keycloak (authorization code) and renders server-side views over the ledger API.
 * The console is the API's first real consumer and is deliberately built as one — the core
 * stays a pure resource server; its security posture does not change at all.
 *
 * <p>Runs on port 8090 either as a compose service
 * ({@code docker compose --profile console up}, the demo topology) or on the host
 * ({@code ./gradlew :console:bootRun}, the inner loop) — the same code path, because since
 * M8-stretch the OIDC provider is described by a browser/in-network URL pair that simply
 * collapses to one URL on the host ({@code ConsoleOidcConfig}). Nothing here dials the
 * provider at startup, so Keycloak is not a startup dependency.
 */
@SpringBootApplication
public class ConsoleApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConsoleApplication.class, args);
    }
}
