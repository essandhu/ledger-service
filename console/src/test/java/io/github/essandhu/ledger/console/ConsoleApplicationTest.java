package io.github.essandhu.ledger.console;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientProperties;
import org.springframework.core.env.Environment;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

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
    Environment env;

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
    @DisplayName("the yaml pins its own documented trap: registration `keycloak` with the openid scope")
    void oauth2_client_properties_pin_the_openid_trap() {
        // Every console test suppresses Boot's property-driven registration (the
        // TestClientRegistrations bean), so the production yaml would otherwise go
        // unproven — bind it directly and pin the values the realm and chain hardcode.
        OAuth2ClientProperties props = Binder.get(env)
                .bind("spring.security.oauth2.client", OAuth2ClientProperties.class)
                .get();
        OAuth2ClientProperties.Registration keycloak = props.getRegistration().get("keycloak");
        assertThat(keycloak)
                .as("registration id is hardcoded in the realm's redirectUris")
                .isNotNull();
        assertThat(keycloak.getScope())
                .as("without openid, login silently downgrades: no ID token, no chips, local-only logout")
                .contains("openid");
        assertThat(keycloak.getClientId()).isEqualTo("ledger-console");
        assertThat(props.getProvider().get("keycloak").getUserNameAttribute())
                .as("discovery alone would leave the principal name on `sub`")
                .isEqualTo("preferred_username");
    }
}
