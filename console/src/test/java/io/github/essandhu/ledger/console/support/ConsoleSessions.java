package io.github.essandhu.ledger.console.support;

import java.util.List;
import java.util.Map;

import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import io.github.essandhu.ledger.console.config.ConsoleRealmRoleMapper;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;

/**
 * OIDC sessions for console tests, claims routed through the PRODUCTION
 * {@link ConsoleRealmRoleMapper} (the house convention: never grant authorities the mapper
 * didn't produce). The registration is the {@code keycloak} one from
 * {@link TestClientRegistrations}, so the relay's authentication-based registration-id
 * resolver finds the seated authorized client.
 */
public final class ConsoleSessions {

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
                .clientRegistration(TestClientRegistrations.keycloak())
                .oidcUser(new DefaultOidcUser(authorities, idToken, "preferred_username"));
    }
}
