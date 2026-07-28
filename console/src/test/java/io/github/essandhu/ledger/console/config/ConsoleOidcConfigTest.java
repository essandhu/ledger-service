package io.github.essandhu.ledger.console.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistration.ProviderDetails;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The containerization contract (M8-stretch, ADR-0007), asserted where it can be asserted
 * without a network: <em>which side of the split each endpoint lands on</em>.
 *
 * <p>This is the test that fails if someone "simplifies" the registration back to a single
 * issuer URL. In the compose topology that mistake has two distinct failure modes, and both
 * are silent in the wrong direction: a browser-facing token/JWKS URL means the console JVM
 * dials its own loopback, and an in-network authorization or logout URL means the BROWSER is
 * sent to a hostname only the compose network can resolve.
 */
@DisplayName("Console OIDC registration (M8-stretch): browser URLs and in-network URLs are not the same URLs")
class ConsoleOidcConfigTest {

    private static final String BROWSER = "http://localhost:8081/realms/ledger";
    private static final String NETWORK = "http://keycloak:8080/realms/ledger";

    /** What `docker compose --profile console up` supplies. */
    private static ClientRegistration containerized() {
        return ConsoleOidcConfig.keycloak(
                new ConsoleOidcProperties(BROWSER, NETWORK, "ledger-console", "secret"));
    }

    /** What a bare `./gradlew :console:bootRun` supplies — the yaml's defaults. */
    private static ClientRegistration hostRun() {
        return ConsoleOidcConfig.keycloak(
                new ConsoleOidcProperties(BROWSER, BROWSER, "ledger-console", "secret"));
    }

    @Nested
    @DisplayName("Containerized topology")
    class Containerized {

        @Test
        @DisplayName("the browser is only ever sent to the host-published Keycloak")
        void browser_facing_endpoints_use_the_browser_url() {
            ProviderDetails provider = containerized().getProviderDetails();

            assertThat(provider.getAuthorizationUri())
                    .isEqualTo(BROWSER + "/protocol/openid-connect/auth");
            assertThat(provider.getConfigurationMetadata().get("end_session_endpoint"))
                    .as("RP-initiated logout is a browser redirect like any other")
                    .isEqualTo(BROWSER + "/protocol/openid-connect/logout");
        }

        @Test
        @DisplayName("everything this JVM dials itself uses the in-network name")
        void jvm_facing_endpoints_use_the_network_url() {
            ProviderDetails provider = containerized().getProviderDetails();

            assertThat(provider.getTokenUri()).isEqualTo(NETWORK + "/protocol/openid-connect/token");
            assertThat(provider.getJwkSetUri()).isEqualTo(NETWORK + "/protocol/openid-connect/certs");
        }

        @Test
        @DisplayName("the issuer is the BROWSER url — it is what the ID token's iss claim says")
        void issuer_is_browser_facing_so_the_id_token_check_stays_armed() {
            // OidcIdTokenValidator compares the token's `iss` against exactly this value (not
            // against discovery metadata, which is why the check survives at all here). The
            // stack pins KC_HOSTNAME to the browser URL, so Keycloak stamps this same string.
            // Setting the in-network URL here would fail every login; leaving it NULL would
            // silently disable the check instead.
            assertThat(containerized().getProviderDetails().getIssuerUri()).isEqualTo(BROWSER);
        }
    }

    @Nested
    @DisplayName("Host topology")
    class HostRun {

        @Test
        @DisplayName("both halves collapse to one URL — the same code path, no profile fork")
        void host_run_needs_no_split_at_all() {
            ProviderDetails provider = hostRun().getProviderDetails();

            assertThat(provider.getAuthorizationUri()).startsWith(BROWSER);
            assertThat(provider.getTokenUri()).startsWith(BROWSER);
            assertThat(provider.getJwkSetUri()).startsWith(BROWSER);
        }
    }

    @Nested
    @DisplayName("Registration shape")
    class Shape {

        @Test
        @DisplayName("openid stays in scope — without it there is no ID token, and with it go the roles")
        void openid_scope_is_present() {
            // The trap the yaml used to carry a comment about: plain OAuth2 login succeeds
            // happily, and only the chips, the whoami claims and RP-initiated logout go
            // missing.
            assertThat(hostRun().getScopes()).contains("openid", "profile");
        }

        @Test
        @DisplayName("the principal is the username, not OIDC's default sub UUID")
        void principal_name_is_the_username() {
            assertThat(hostRun().getProviderDetails().getUserInfoEndpoint()
                    .getUserNameAttributeName()).isEqualTo("preferred_username");
        }

        @Test
        @DisplayName("no userinfo endpoint — the console reads the user from the ID token, so it must not fetch one")
        void userinfo_is_deliberately_absent() {
            // Configuring it is what makes OidcUserService call it: the profile scope is in
            // the request, so a non-null URI here means a network round trip on every login
            // whose claims nothing reads (roles come off getIdToken(), by ADR-0007 decision 6).
            // Discovery gave no way to decline it; a hand-built registration does. The
            // username attribute above survives its absence — the two are independent.
            assertThat(hostRun().getProviderDetails().getUserInfoEndpoint().getUri()).isNull();
        }

        @Test
        @DisplayName("the redirect URI template resolves per request — the realm registers the resolved one")
        void redirect_uri_is_the_baseurl_template() {
            assertThat(hostRun().getRedirectUri())
                    .isEqualTo("{baseUrl}/login/oauth2/code/{registrationId}");
            assertThat(hostRun().getRegistrationId())
                    .as("spelled out in the realm's redirectUris and in every login-bounce assertion")
                    .isEqualTo("keycloak");
        }

        @Test
        @DisplayName("a trailing slash on the configured issuer does not double the path separator")
        void trailing_slashes_are_tolerated() {
            ClientRegistration registration = ConsoleOidcConfig.keycloak(
                    new ConsoleOidcProperties(BROWSER + "/", NETWORK + "/", "ledger-console", "s"));

            assertThat(registration.getProviderDetails().getAuthorizationUri())
                    .isEqualTo(BROWSER + "/protocol/openid-connect/auth");
            assertThat(registration.getProviderDetails().getTokenUri())
                    .isEqualTo(NETWORK + "/protocol/openid-connect/token");
        }
    }
}
