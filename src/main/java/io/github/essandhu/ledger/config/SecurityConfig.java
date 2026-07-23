package io.github.essandhu.ledger.config;

import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Pure OAuth2 resource server (PLAN §7). I13's first layer: request-matcher rules per the
 * PLAN §5 role table — one role per endpoint, deliberately NO role hierarchy (ADMIN does not
 * imply READ; convenience bundles belong in Keycloak composite roles, keeping this server a
 * literal transcription of the table). The second layer is {@code @PreAuthorize} on the
 * use-case methods, enabled here.
 *
 * <p>Known deviation, accepted for M1: PLAN §5 marks {@code /actuator/prometheus} as
 * management-port-internal; it currently shares port 8080 and is reachable by ANY authenticated
 * principal. Resolution (management-port split in compose, or a dedicated role) is ops polish —
 * tracked for M7.
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        ProblemAuthResponses problemResponses = new ProblemAuthResponses();
        JwtAuthenticationConverter jwtConverter = new JwtAuthenticationConverter();
        jwtConverter.setJwtGrantedAuthoritiesConverter(new LedgerRealmRoleConverter());
        http
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(EndpointRequest.to("health")).permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/accounts").hasRole("LEDGER_ADMIN")
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/accounts/*").hasRole("LEDGER_ADMIN")
                        // HEAD listed explicitly: Spring MVC serves HEAD through @GetMapping
                        // handlers, and a GET-only matcher would let HEAD fall through to the
                        // namespace backstop below.
                        .requestMatchers(HttpMethod.GET, "/api/v1/accounts", "/api/v1/accounts/*")
                        .hasRole("LEDGER_READ")
                        .requestMatchers(HttpMethod.HEAD, "/api/v1/accounts", "/api/v1/accounts/*")
                        .hasRole("LEDGER_READ")
                        // M2 money movers (PLAN §5): one role, LEDGER_WRITE — no hierarchy, so
                        // ADMIN posts nothing and WRITE reads nothing.
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/journal-entries",
                                "/api/v1/journal-entries/*/reversal",
                                "/api/v1/transfers")
                        .hasRole("LEDGER_WRITE")
                        // HEAD listed explicitly, same reason as the account matchers above.
                        // Only the item GET exists — the collection listing is M3, so
                        // GET /api/v1/journal-entries stays on the backstop below.
                        .requestMatchers(HttpMethod.GET, "/api/v1/journal-entries/*")
                        .hasRole("LEDGER_READ")
                        .requestMatchers(HttpMethod.HEAD, "/api/v1/journal-entries/*")
                        .hasRole("LEDGER_READ")
                        // Namespace backstop: within /api/v1 only the (method, path) pairs
                        // granted above exist — new endpoints must add explicit rules, and an
                        // unlisted method (PUT, DELETE, ...) is 403 for everyone.
                        .requestMatchers("/api/v1/**").denyAll()
                        // Everything else — remaining actuator, springdoc, unknown paths —
                        // requires a valid JWT: default-deny stays the baseline (M0 seed of I13).
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter))
                        .authenticationEntryPoint(problemResponses)
                        .accessDeniedHandler(problemResponses))
                // Stateless bearer-token API: no sessions, hence nothing for CSRF to protect.
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable);
        return http.build();
    }
}
