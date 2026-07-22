package io.github.essandhu.ledger.config;

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
}
