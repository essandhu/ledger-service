package io.github.essandhu.ledger.console.support;

import java.util.List;
import java.util.Map;

import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import io.github.essandhu.ledger.console.config.ConsoleOidcConfig;
import io.github.essandhu.ledger.console.config.ConsoleOidcProperties;
import io.github.essandhu.ledger.console.config.ConsoleRealmRoleMapper;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;

/**
 * OIDC sessions for console tests, claims routed through the PRODUCTION
 * {@link ConsoleRealmRoleMapper} (the house convention: never grant authorities the mapper
 * didn't produce).
 */
public final class ConsoleSessions {

    /**
     * The registration {@code oidcLogin()} seats its authorized client against, built by the
     * PRODUCTION factory from the production yaml's host-topology defaults — a lookalike
     * assembled by hand here is exactly what M8-stretch removed. Only the registration id is
     * load-bearing (the relay resolves it off the authentication and then looks the
     * registration up in the real repository bean), but building the real shape means a
     * change to it cannot pass unnoticed here.
     *
     * <p>The context's own registration comes from the application yaml, and since
     * M8-stretch that is the PRODUCTION bean in every console test: with discovery gone,
     * nothing about it needs a live Keycloak to assemble.
     */
    public static final ClientRegistration KEYCLOAK = ConsoleOidcConfig.keycloak(
            new ConsoleOidcProperties("http://localhost:8081/realms/ledger",
                    "http://localhost:8081/realms/ledger", "ledger-console", "test-secret"));

    private ConsoleSessions() {
    }

    /** An OIDC session for {@code username} whose ID token carries the given realm roles. */
    public static RequestPostProcessor user(String username, String... realmRoles) {
        OidcIdToken idToken = OidcIdToken.withTokenValue("id-token")
                .claim("sub", username)
                .claim("preferred_username", username)
                .claim("realm_access", Map.of("roles", List.of(realmRoles)))
                .build();
        var authorities = new ConsoleRealmRoleMapper()
                .mapAuthorities(List.of(new OidcUserAuthority(idToken)));
        return oidcLogin()
                .clientRegistration(KEYCLOAK)
                .oidcUser(new DefaultOidcUser(authorities, idToken, "preferred_username"));
    }
}
