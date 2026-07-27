package io.github.essandhu.ledger.config;

import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

/**
 * OpenAPI metadata for the springdoc-generated spec (/v3/api-docs). The spec itself is derived
 * from the controllers; CI publishes it as a build artifact, produced by the integration test
 * that asserts its content (so the artifact can never be stale or unverified).
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

    static final String BEARER_JWT = "bearer-jwt";

    /**
     * The ADR-0004 client-facing contract, discharged at M7: every operation that requires
     * {@code Idempotency-Key} gets the same semantics text, keyed off the parameter itself so
     * a future money mover is documented by construction — there is no list of paths to forget
     * to extend. Two behaviors deliberately deviate from the IETF idempotency-key draft and
     * MUST stay documented: concurrent duplicates block-and-answer (no 409-while-in-flight),
     * and responses are not immutable over time (only successes are recorded).
     */
    static final String IDEMPOTENCY_SEMANTICS = """

            **Idempotency (ADR-0004).** The required `Idempotency-Key` header (any string \
            without commas or control characters, at most 200 characters) makes this operation \
            safe to retry. Scope is (authenticated principal, key) across all money-moving \
            endpoints. Replaying a successful call returns `200` with the original response \
            body byte-for-byte, `Idempotency-Replayed: true`, and no `Location` header. \
            Reusing a key with a different payload is `422` (`idempotency-key-conflict`), with \
            zero side effects. Two deviations from the IETF idempotency-key draft: a concurrent \
            duplicate briefly *blocks* and then answers definitively instead of failing `409` \
            while the first attempt is in flight; and only successful outcomes are recorded, so \
            the response for a key is not immutable over time — a rejected attempt (e.g. `422` \
            overdraft) followed by a successful retry with the same key yields `201` then \
            replays `200`.""";

    @Bean
    OpenAPI ledgerOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Ledger Service")
                        .description("Standalone double-entry ledger: accounts, postings, balances. "
                                + "All endpoints except /actuator/health require a bearer JWT from "
                                + "the configured issuer; errors are RFC 9457 problem documents.")
                        .version("v1"))
                .components(new Components().addSecuritySchemes(BEARER_JWT, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_JWT));
    }

    @Bean
    OperationCustomizer idempotencySemantics() {
        return (operation, handlerMethod) -> {
            if (operation.getParameters() == null) {
                return operation;
            }
            operation.getParameters().stream()
                    .filter(parameter -> "Idempotency-Key".equals(parameter.getName()))
                    .findFirst()
                    .ifPresent(parameter -> {
                        parameter.description(
                                "Client-chosen retry key; see the operation description for "
                                        + "replay, conflict, and concurrency semantics (ADR-0004).");
                        String description = operation.getDescription() == null
                                ? IDEMPOTENCY_SEMANTICS.stripLeading()
                                : operation.getDescription() + IDEMPOTENCY_SEMANTICS;
                        operation.description(description);
                    });
            return operation;
        };
    }
}
