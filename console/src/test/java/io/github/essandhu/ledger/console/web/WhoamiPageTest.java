package io.github.essandhu.ledger.console.web;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import io.github.essandhu.ledger.console.config.ConsoleRealmRoleMapper;
import io.github.essandhu.ledger.console.support.ConsoleSessions;
import io.github.essandhu.ledger.console.support.ConsoleWebTest;
import io.github.essandhu.ledger.console.support.TestClientRegistrations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

/**
 * The M8a demo, as tests: log in and see the role chips; log out and land on Keycloak.
 * Sessions are minted with claims routed through the PRODUCTION {@link ConsoleRealmRoleMapper}
 * (the core's convention: never grant authorities the mapper didn't produce).
 *
 * <p>{@code oidcLogin()} bypasses the login chain itself — the chain's own
 * {@code userAuthoritiesMapper} wiring is discharged by {@link ConsoleLoginCallbackTest},
 * the way the core pairs its matrix tests with the CI smoke's real token calls.
 */
@ConsoleWebTest
@DisplayName("Whoami page (M8a): login round-trip, role chips, RP-initiated logout")
class WhoamiPageTest {

    @Autowired
    MockMvcTester mvc;

    private static RequestPostProcessor user(String username, String... realmRoles) {
        return ConsoleSessions.user(username, realmRoles);
    }

    @Nested
    @DisplayName("Login")
    class Login {

        @Test
        @DisplayName("unauthenticated hit goes straight to Keycloak — one registration, no chooser page")
        void unauthenticated_redirects_to_keycloak_login() {
            assertThat(mvc.get().uri("/whoami"))
                    .hasStatus3xxRedirection()
                    .hasRedirectedUrl("/oauth2/authorization/keycloak");
        }

        @Test
        @DisplayName("the root is the whoami page")
        void root_redirects_to_whoami() {
            assertThat(mvc.get().uri("/").with(user("ops", "CONSOLE_OPS")))
                    .hasStatus3xxRedirection()
                    .hasRedirectedUrl("/whoami");
        }
    }

    @Nested
    @DisplayName("Role chips")
    class RoleChips {

        @Test
        @DisplayName("ops sees the CONSOLE_OPS bundle chip next to the three roles it expands to")
        void ops_sees_composite_and_expanded_roles() {
            // ">NAME<" pins the value as element TEXT (a chip or the account line), not a
            // substring of some attribute; the subsequence pins the sorted render order.
            assertThat(mvc.get().uri("/whoami")
                    .with(user("ops", "CONSOLE_OPS", "LEDGER_READ", "LEDGER_ADMIN", "LEDGER_METRICS")))
                    .hasStatusOk()
                    .bodyText()
                    .contains("Signed in as", "role-chip bundle")
                    .containsSubsequence(">ops<",
                            ">CONSOLE_OPS<", ">LEDGER_ADMIN<", ">LEDGER_METRICS<", ">LEDGER_READ<")
                    // The display strips the authority prefix — a regression re-renders
                    // chips as ROLE_* and breaks the bundle styling with it.
                    .doesNotContain("ROLE_");
        }

        @Test
        @DisplayName("viewer sees exactly one chip — the other side of the matrix")
        void viewer_sees_only_read() {
            assertThat(mvc.get().uri("/whoami").with(user("viewer", "LEDGER_READ")))
                    .hasStatusOk()
                    .bodyText()
                    .contains(">viewer<", ">LEDGER_READ<")
                    .doesNotContain("LEDGER_ADMIN", "LEDGER_WRITE", "LEDGER_METRICS", "CONSOLE_OPS",
                            "role-chip bundle", "ROLE_");
        }

        @Test
        @DisplayName("Keycloak's default roles never render — the empty state does")
        void foreign_roles_render_the_empty_state() {
            assertThat(mvc.get().uri("/whoami")
                    .with(user("stranger", "offline_access", "uma_authorization")))
                    .hasStatusOk()
                    .bodyText()
                    .contains("No ledger roles on this account")
                    .doesNotContain("offline_access", "uma_authorization");
        }
    }

    @Nested
    @DisplayName("Logout")
    class Logout {

        @Test
        @DisplayName("sign-out is RP-initiated: Keycloak end_session with id_token_hint, back to the console root")
        void logout_lands_on_keycloak_end_session() {
            MvcTestResult result = mvc.post().uri("/logout")
                    .with(user("ops", "CONSOLE_OPS"))
                    .with(csrf())
                    .exchange();

            assertThat(result).hasStatus3xxRedirection();
            String location = result.getResponse().getRedirectedUrl();
            assertThat(location)
                    .startsWith(TestClientRegistrations.END_SESSION_ENDPOINT)
                    .contains("id_token_hint=")
                    // {baseUrl} resolved against the request — MockMvc's base is http://localhost.
                    .contains("post_logout_redirect_uri=http://localhost");
        }

        @Test
        @DisplayName("logout without a CSRF token is refused — it is a state-changing POST")
        void logout_without_csrf_is_refused() {
            assertThat(mvc.post().uri("/logout").with(user("ops", "CONSOLE_OPS")))
                    .hasStatus(HttpStatus.FORBIDDEN);
        }
    }
}
