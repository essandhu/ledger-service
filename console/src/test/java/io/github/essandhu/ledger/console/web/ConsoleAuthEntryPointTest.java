package io.github.essandhu.ledger.console.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import io.github.essandhu.ledger.console.support.ConsoleWebTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How an expired session is answered depends on WHO asked (M8-stretch): a browser navigation
 * is bounced to Keycloak, an htmx fragment request is refused with a status.
 *
 * <p>The bug this closes is not theoretical — it is what the polled drift badge would have
 * done every time a session died. htmx follows the 302 at the XHR level, receives Keycloak's
 * login page with a 200, and swaps that HTML into whatever target asked for a fragment: the
 * topbar, or a table row inside a statement. The console's own load-more had the same hazard
 * from M8b; putting a poller in the page chrome on every page turned "eventually" into
 * "within one polling interval".
 *
 * <p>Both branches are asserted because the fix is ORDER-dependent: the 401 mapping is
 * registered from the security DSL so it precedes the one {@code oauth2Login} adds during its
 * own init, and a delegating entry point takes the first matching one. Assert only the htmx
 * side and a reordering silently sends real browsers a 401 instead of a login page.
 */
@ConsoleWebTest
@DisplayName("Auth entry point (M8-stretch): browsers get Keycloak, htmx gets a status")
class ConsoleAuthEntryPointTest {

    @Autowired
    MockMvcTester mvc;

    @Test
    @DisplayName("an htmx request on a dead session is refused 401 with HX-Refresh — the browser reloads itself")
    void htmx_requests_are_refused_with_a_status() {
        MvcTestResult result = mvc.get().uri("/reconciliation/drift-badge")
                .header("HX-Request", "true")
                .exchange();

        assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED);
        // The HEADER carries the meaning, not the status: the console mirrors the LEDGER's
        // 401 elsewhere (ConsoleErrorAdvice, when the relayed token is rejected), and that one
        // must keep rendering as a problem document rather than silently reloading the page.
        // htmx acts on HX-Refresh before it decides anything about swapping, so this works on
        // a 401 exactly as it would on a 200.
        assertThat(result.getResponse().getHeader("HX-Refresh")).isEqualTo("true");
    }

    @Test
    @DisplayName("the same rule covers every fragment endpoint, not just the badge")
    void the_rule_is_not_badge_specific() {
        // The statement's load-more sentinel is the other htmx caller, and it predates the
        // badge — this is the regression it always deserved.
        assertThat(mvc.get().uri("/accounts").header("HX-Request", "true"))
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("a real navigation still bounces to Keycloak — the fix must not swallow the login flow")
    void plain_navigations_still_redirect_to_keycloak() {
        assertThat(mvc.get().uri("/accounts"))
                .hasStatus3xxRedirection()
                .hasRedirectedUrl("/oauth2/authorization/keycloak");
    }

    @Test
    @DisplayName("HX-Request with any other value is a browser as far as this rule is concerned")
    void the_matcher_is_exact() {
        // RequestHeaderRequestMatcher compares the value, not just presence; pinned so a
        // loosening to presence-only is a deliberate act rather than a silent one.
        assertThat(mvc.get().uri("/accounts").header("HX-Request", "false"))
                .hasStatus3xxRedirection()
                .hasRedirectedUrl("/oauth2/authorization/keycloak");
    }
}
