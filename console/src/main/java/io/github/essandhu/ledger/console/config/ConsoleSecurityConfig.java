package io.github.essandhu.ledger.console.config;

import jakarta.servlet.DispatcherType;

import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.oidc.session.InMemoryOidcSessionRegistry;
import org.springframework.security.oauth2.client.oidc.session.OidcSessionRegistry;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.DelegatingAuthenticationEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import org.springframework.security.web.util.matcher.RequestHeaderRequestMatcher;

/**
 * The console's security chain (ADR-0007): OAuth2/OIDC <em>client</em> login against
 * Keycloak, session-based like any server-rendered app — the deliberate inverse of the
 * core's stateless resource server. CSRF stays ON (browser sessions need it; the core
 * disables it because bearer-token requests carry no ambient credentials).
 *
 * <p>Defining this chain makes Boot's default OAuth2 login chain back off
 * ({@code @ConditionalOnDefaultWebSecurity}), so {@code oauth2Login} must be — and is —
 * configured explicitly; forgetting it yields 403s, not an error.
 *
 * <p>Logout runs in BOTH directions since M8-stretch. RP-initiated (below) ends the Keycloak
 * SSO session when a user signs out here; back-channel logout ends THIS session when Keycloak
 * kills the SSO session behind the console's back — an administrator revoking a session, or a
 * sign-out in another client. ADR-0007 deferred the second half to the containerized stretch
 * for one concrete reason: Keycloak has to be able to reach a console URL, and it cannot dial
 * a host-run process from inside its own container. Containerizing supplied the URL, so the
 * loose end is closed rather than left open.
 *
 * <p>There are deliberately NO role rules here, not even for M8c's sweep trigger (the one
 * write the console offers). A {@code hasRole("LEDGER_ADMIN")} matcher on that path was
 * considered and rejected: it would fork the role matrix into a second place that can drift
 * from the API's, which is the authority — every console request rides the user's own token,
 * so the ledger already answers 403 and {@code ConsoleErrorAdvice} renders that honestly. The
 * console's job is to not OFFER what will be refused ({@code sec:authorize} hides the button),
 * not to re-adjudicate it. {@code ReconciliationPageTest} pins both halves.
 */
@Configuration(proxyBeanMethods = false)
class ConsoleSecurityConfig {

    /**
     * The answer to an htmx request whose session is gone: a status the swap machinery will
     * not render, plus the header that tells htmx to reload the whole page instead.
     * {@code setStatus} rather than {@code sendError} — the latter would run the ERROR
     * dispatch and put an error page in the body of a response nothing reads.
     */
    private static final AuthenticationEntryPoint HTMX_SESSION_GONE = (request, response, ex) -> {
        response.setHeader("HX-Refresh", "true");
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
    };

    @Bean
    SecurityFilterChain consoleSecurityFilterChain(
            HttpSecurity http, ClientRegistrationRepository clientRegistrations) throws Exception {
        // RP-initiated logout: redirects to Keycloak's end_session endpoint with the ID token
        // as hint, then back to the console root. Requires BOTH the openid scope (no id_token,
        // no hint — the handler silently degrades to a local-only logout) and the client's
        // post.logout.redirect.uris attribute in the realm (missing = Keycloak error page,
        // session left ACTIVE). The endpoint itself is browser-facing metadata the console
        // now builds itself (ConsoleOidcConfig) rather than discovering.
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
                        // static assets a browser needs — CSS, JS (htmx + the time
                        // localizer), and favicon must survive the Keycloak-bounce round
                        // trip without a session.
                        .requestMatchers(EndpointRequest.to("health")).permitAll()
                        .requestMatchers("/css/**", "/js/**", "/favicon.ico").permitAll()
                        // Everything else — including unknown paths — demands login first.
                        // Note what is NOT listed: the back-channel logout endpoint. Its
                        // filter is installed BEFORE CsrfFilter and answers the request
                        // itself, so authorization (and CSRF) never run for it — a permitAll
                        // rule here would be dead config implying a check that never happens.
                        .anyRequest().authenticated())
                .oauth2Login(login -> login
                        // Realm roles land as ROLE_* authorities via the production mapper —
                        // wired explicitly (not via bean discovery) so the dependency is
                        // visible right here.
                        .userInfoEndpoint(userInfo ->
                                userInfo.userAuthoritiesMapper(new ConsoleRealmRoleMapper())))
                // Back-channel logout (M8-stretch): Keycloak POSTs a signed logout token to
                // /logout/connect/back-channel/keycloak, Spring validates it against the
                // registration (its jwkSetUri and issuer — the in-network and browser halves
                // of the split respectively, both already correct) and invalidates the
                // matching session. Configuring this is also what makes oauth2Login start
                // recording sessions in the OidcSessionRegistry at all; without it the
                // registry stays empty and every logout token matches nothing.
                .oidcLogout(logout -> logout.backChannel(Customizer.withDefaults()))
                // An htmx fragment request on a dead session must not be answered with
                // Keycloak's LOGIN PAGE: the XHR follows the redirect, gets 200 HTML, and htmx
                // swaps a login form into whatever target asked — a topbar, a table row. A
                // machine request gets a machine answer, and the answer is htmx's own
                // protocol: HX-Refresh makes the browser reload at the TOP level, where a
                // login bounce belongs (the same reasoning as M8c's HX-Redirect on the sweep
                // trigger). The header, not the status, is what carries that meaning — the
                // console deliberately MIRRORS the ledger's 401 elsewhere (ConsoleErrorAdvice
                // renders it as a problem document), and those two must not be confused.
                //
                // Set WHOLE, not contributed to via defaultAuthenticationEntryPointFor: that
                // path appends to a list oauth2Login also appends to during its init, and the
                // builder promotes the FIRST registered entry point to be the fallback — so
                // adding the 401 mapping there quietly makes 401 the answer for real browser
                // navigations too (caught by ConsoleAuthEntryPointTest's redirect cells, which
                // is why they exist). One object, stated here, is worth more than an ordering
                // argument. The delegate reproduces the login bounce Security would install
                // for this app: a single registration and no custom login page, so the URL is
                // the one every test already asserts.
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(
                        DelegatingAuthenticationEntryPoint.builder()
                                .addEntryPointFor(HTMX_SESSION_GONE,
                                        new RequestHeaderRequestMatcher("HX-Request", "true"))
                                .defaultEntryPoint(new LoginUrlAuthenticationEntryPoint(
                                        "/oauth2/authorization/" + ConsoleOidcConfig.REGISTRATION_ID))
                                .build()))
                // M8b brought the console's first JavaScript; the CSP makes one future
                // escaping slip a blocked resource instead of an XSS. Everything is
                // self-hosted by design (ADR-0007 vendors htmx), so 'self' costs nothing.
                .headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives(
                        "default-src 'self'; script-src 'self'; style-src 'self'; "
                                + "img-src 'self'; frame-ancestors 'none'")))
                .logout(logout -> logout.logoutSuccessHandler(oidcLogout));
        return http.build();
    }

    /**
     * The correlation between a Keycloak SSO session and this console's HTTP session, which
     * is what a logout token is matched against. Security would fall back to an identical
     * in-memory instance held as a filter-chain shared object; declaring the bean instead
     * follows the precedent {@code ApiClientConfig} set with the authorized-client manager —
     * a component this important should be visible and injectable, and a test that can reach
     * it can prove sessions are actually being recorded rather than assuming it.
     *
     * <p>In memory, and therefore per instance: a multi-replica console would need a shared
     * registry (Spring Session), because a logout token can only reach one replica. Single
     * instance is this stack's premise everywhere else too.
     */
    @Bean
    OidcSessionRegistry oidcSessionRegistry() {
        return new InMemoryOidcSessionRegistry();
    }

    /**
     * Servlet session events, republished as Spring events. Not decoration: the session
     * registry above is otherwise never told when a session dies of natural causes, so
     * ordinary sign-outs and timeouts would accumulate in it for the life of the process.
     */
    @Bean
    HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }
}
