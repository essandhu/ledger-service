package io.github.essandhu.ledger.console;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import io.github.essandhu.ledger.console.config.ConsoleOidcConfig;
import io.github.essandhu.ledger.console.support.ConsoleWebTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The console's walking skeleton: the full production context (security chain, templates,
 * actuator) assembles, and the anonymous surface is exactly health + static assets — the
 * same secure-by-default posture as the core, expressed for a browser app.
 */
@ConsoleWebTest
@DisplayName("Console walking skeleton (M8a): context assembles, anonymous surface is health + static assets")
class ConsoleApplicationTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    ClientRegistrationRepository clientRegistrations;

    @Test
    @DisplayName("health answers anonymously — compose/liveness needs no session")
    void health_is_anonymous() {
        assertThat(mvc.get().uri("/actuator/health")).hasStatusOk();
    }

    @Test
    @DisplayName("the stylesheet is anonymous — the Keycloak-bounce round trip must not 302 assets")
    void stylesheet_is_anonymous() {
        assertThat(mvc.get().uri("/css/console.css")).hasStatusOk();
    }

    @Test
    @DisplayName("the scripts are anonymous — htmx and the time localizer survive the bounce too")
    void scripts_are_anonymous() {
        assertThat(mvc.get().uri("/js/htmx.min.js")).hasStatusOk();
        assertThat(mvc.get().uri("/js/app.js")).hasStatusOk();
    }

    @Test
    @DisplayName("every page carries the self-only CSP — the console's first JS shipped with a leash")
    void csp_header_present() {
        assertThat(mvc.get().uri("/css/console.css"))
                .hasStatusOk()
                .containsHeader("Content-Security-Policy");
        assertThat(mvc.get().uri("/css/console.css").exchange()
                .getResponse().getHeader("Content-Security-Policy"))
                .contains("default-src 'self'", "frame-ancestors 'none'");
    }

    @Test
    @DisplayName("unknown paths demand login before they can 404 — the backstop is authentication")
    void unknown_paths_require_login_first() {
        assertThat(mvc.get().uri("/does-not-exist"))
                .hasStatus3xxRedirection()
                .hasRedirectedUrl("/oauth2/authorization/keycloak");
    }

    @Test
    @DisplayName("sibling actuator paths demand login — 'health only' holds in both directions")
    void other_actuator_paths_require_login() {
        // /actuator (the discovery page) goes red the moment to("health") widens to
        // toAnyEndpoint(); /actuator/info additionally catches an exposure widening.
        for (String path : List.of("/actuator", "/actuator/info")) {
            assertThat(mvc.get().uri(path))
                    .hasStatus3xxRedirection()
                    .hasRedirectedUrl("/oauth2/authorization/keycloak");
        }
    }

    @Test
    @DisplayName("the context assembles the PRODUCTION registration from the production yaml")
    void production_registration_is_the_one_under_test() {
        // Since M8-stretch there is no stand-in registration bean: the eager discovery that
        // forced one is gone, so THIS is the object the whole suite exercises. Pinned here in
        // the one test that owns the production context's shape — the split itself is
        // ConsoleOidcConfigTest's subject.
        ClientRegistration keycloak =
                clientRegistrations.findByRegistrationId(ConsoleOidcConfig.REGISTRATION_ID);

        assertThat(keycloak)
                .as("registration id is hardcoded in the realm's redirectUris")
                .isNotNull();
        assertThat(keycloak.getClientId()).isEqualTo("ledger-console");
        assertThat(keycloak.getScopes())
                .as("without openid, login silently downgrades: no ID token, no chips, local-only logout")
                .contains("openid");
        assertThat(keycloak.getProviderDetails().getAuthorizationUri())
                .as("the yaml's default topology is the host one, so a bare bootRun needs no environment")
                .isEqualTo("http://localhost:8081/realms/ledger/protocol/openid-connect/auth");
    }

    @Test
    @DisplayName("the context loads with NO Keycloak reachable — the eager-discovery constraint is gone")
    void context_assembles_without_a_provider() {
        // The whole point of M8-stretch, asserted as a fact rather than left implied by the
        // suite happening to pass: nothing in the console's startup dials the provider, which
        // is what makes an in-container `localhost:8081` a non-issue. Nothing is stubbed in
        // this context; if the registration went back to `issuer-uri`, this class would not
        // load at all.
        assertThat(clientRegistrations.findByRegistrationId("keycloak").getProviderDetails()
                .getConfigurationMetadata())
                .as("built by hand, not fetched: discovery would have populated far more than this")
                .containsOnlyKeys("end_session_endpoint");
    }
}
