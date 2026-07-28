package io.github.essandhu.ledger.console.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.client.oidc.session.OidcSessionRegistry;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import io.github.essandhu.ledger.console.support.ConsoleWebTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The back-channel logout endpoint exists and is wired (M8-stretch, ADR-0007). What this
 * suite can prove without a provider is the WIRING, and the wiring is where the silent
 * failures live; the real thing — Keycloak signing a logout token for a live session and this
 * console tearing that session down — is a browser-lane cell, because every interesting part
 * of it is a piece MockMvc would have to stub.
 *
 * <p>Three distinct ways this can be broken while looking fine, one assertion each:
 * <ul>
 * <li>the endpoint is not registered at all (the {@code backChannel()} call missing) — then
 * the path falls through to {@code anyRequest().authenticated()} and answers 302, and
 * Keycloak's POST is silently redirected to a login page forever;</li>
 * <li>the endpoint is registered but sits behind CSRF or authorization — Keycloak has neither
 * a CSRF token nor a session, so every logout notification is rejected;</li>
 * <li>the session registry is absent — {@code oauth2Login} only starts recording sessions
 * when an {@code oidcLogout} configurer is present, so without it every logout token
 * validates correctly and then matches nothing.</li>
 * </ul>
 */
@ConsoleWebTest
@DisplayName("Back-channel logout (M8-stretch): the endpoint Keycloak posts to is reachable and unguarded")
class BackChannelLogoutTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired(required = false)
    OidcSessionRegistry sessionRegistry;

    private static final String ENDPOINT = "/logout/connect/back-channel/keycloak";

    @Test
    @DisplayName("an unauthenticated, CSRF-less POST reaches the endpoint — it is refused on the TOKEN, not the caller")
    void the_endpoint_answers_keycloak_not_a_login_page() {
        // Exactly what Keycloak sends, minus a valid token: no session, no CSRF token, form
        // encoded. A 400 means the filter took the request and rejected the logout_token,
        // which is the only correct refusal here. A 302 means the endpoint isn't registered
        // (falling through to the login bounce); a 403 means CSRF or authorization got to it
        // first. Both would make back-channel logout a no-op that nothing else notices.
        assertThat(mvc.post().uri(ENDPOINT).param("logout_token", "not-a-real-token"))
                .hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("a POST with no logout token at all is still the endpoint's own 400")
    void a_tokenless_post_is_the_endpoints_own_refusal() {
        assertThat(mvc.post().uri(ENDPOINT)).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("the session registry exists — without it a valid logout token would match nothing")
    void sessions_are_recorded_for_logout_tokens_to_match() {
        // The registry is created by the oidcLogout configurer and shared with oauth2Login's
        // session-authentication strategy. Its absence is the quietest of the three failures:
        // the endpoint would keep answering correctly and no session would ever end.
        assertThat(sessionRegistry)
                .as("oauth2Login only records OIDC sessions when an oidcLogout configurer is present")
                .isNotNull();
    }
}
