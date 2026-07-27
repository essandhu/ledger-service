package io.github.essandhu.ledger.console.support;

import java.util.Map;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;

/**
 * A real (in-memory, no Mockito — house rule) {@code ClientRegistrationRepository} with the
 * {@code keycloak} registration built from EXPLICIT endpoints. Providing the bean makes
 * Boot's property-driven registration mapping back off ({@code @ConditionalOnMissingBean}),
 * which is the load-bearing part: the production {@code issuer-uri} property triggers eager
 * OIDC discovery at startup, so a context built from it cannot load without a live Keycloak.
 * Tests get the same registration shape — including the {@code end_session_endpoint}
 * metadata the logout handler reads — with zero network.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestClientRegistrations {

    public static final String END_SESSION_ENDPOINT =
            "http://localhost:8081/realms/ledger/protocol/openid-connect/logout";

    @Bean
    ClientRegistrationRepository clientRegistrationRepository() {
        return new InMemoryClientRegistrationRepository(keycloak());
    }

    /** The registration oidcLogin() test requests bind to (registrationId {@code keycloak}). */
    public static ClientRegistration keycloak() {
        return ClientRegistration.withRegistrationId("keycloak")
                .clientId("ledger-console")
                .clientSecret("test-secret")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .scope("openid", "profile")
                .issuerUri("http://localhost:8081/realms/ledger")
                .authorizationUri("http://localhost:8081/realms/ledger/protocol/openid-connect/auth")
                .tokenUri("http://localhost:8081/realms/ledger/protocol/openid-connect/token")
                .jwkSetUri("http://localhost:8081/realms/ledger/protocol/openid-connect/certs")
                .userNameAttributeName("preferred_username")
                .providerConfigurationMetadata(Map.of("end_session_endpoint", END_SESSION_ENDPOINT))
                .build();
    }
}
