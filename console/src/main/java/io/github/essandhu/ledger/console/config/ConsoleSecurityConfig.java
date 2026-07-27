package io.github.essandhu.ledger.console.config;

import jakarta.servlet.DispatcherType;

import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The console's security chain (ADR-0007): OAuth2/OIDC <em>client</em> login against
 * Keycloak, session-based like any server-rendered app — the deliberate inverse of the
 * core's stateless resource server. CSRF stays ON (browser sessions need it; the core
 * disables it because bearer-token requests carry no ambient credentials), and logout is
 * RP-initiated so ending the console session also ends the Keycloak SSO session — one-way
 * only: Keycloak-side revocation does not reach the console (back-channel logout is deferred
 * to the containerized stretch, an accepted ADR-0007 trade-off).
 *
 * <p>Defining this chain makes Boot's default OAuth2 login chain back off
 * ({@code @ConditionalOnDefaultWebSecurity}), so {@code oauth2Login} must be — and is —
 * configured explicitly; forgetting it yields 403s, not an error.
 */
@Configuration(proxyBeanMethods = false)
class ConsoleSecurityConfig {

    @Bean
    SecurityFilterChain consoleSecurityFilterChain(
            HttpSecurity http, ClientRegistrationRepository clientRegistrations) throws Exception {
        // RP-initiated logout: redirects to Keycloak's end_session endpoint with the ID token
        // as hint, then back to the console root. Requires BOTH the openid scope (no id_token,
        // no hint — the handler silently degrades to a local-only logout) and the client's
        // post.logout.redirect.uris attribute in the realm (missing = Keycloak error page,
        // session left ACTIVE).
        OidcClientInitiatedLogoutSuccessHandler oidcLogout =
                new OidcClientInitiatedLogoutSuccessHandler(clientRegistrations);
        oidcLogout.setPostLogoutRedirectUri("{baseUrl}");

        http
                .authorizeHttpRequests(authorize -> authorize
                        // Error rendering must not re-demand auth: without this, a permitted
                        // but missing asset (favicon, a mistyped css path) 404s into the
                        // ERROR dispatch, hits anyRequest(), and 302s to Keycloak with a
                        // ;jsessionid — the REQUEST dispatch below still authenticates first.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        // Anonymous surface (ADR-0007): the core's health rule plus the
                        // static assets a browser needs — CSS and favicon must survive the
                        // Keycloak-bounce round trip without a session.
                        .requestMatchers(EndpointRequest.to("health")).permitAll()
                        .requestMatchers("/css/**", "/favicon.ico").permitAll()
                        // Everything else — including unknown paths — demands login first.
                        .anyRequest().authenticated())
                .oauth2Login(login -> login
                        // Realm roles land as ROLE_* authorities via the production mapper —
                        // wired explicitly (not via bean discovery) so the dependency is
                        // visible right here.
                        .userInfoEndpoint(userInfo ->
                                userInfo.userAuthoritiesMapper(new ConsoleRealmRoleMapper())))
                .logout(logout -> logout.logoutSuccessHandler(oidcLogout));
        return http.build();
    }
}
