package io.github.essandhu.ledger.console.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import io.github.essandhu.ledger.console.support.TestClientRegistrations;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the PRODUCTION oauth2Login chain end to end — callback, token exchange, ID-token
 * decode, authorities mapping, session — with the two network legs stubbed at the bean seams
 * Spring Security itself exposes for exactly this. This is the test that goes red if
 * {@code ConsoleSecurityConfig}'s {@code userAuthoritiesMapper} wiring is dropped:
 * {@code WhoamiPageTest}'s {@code oidcLogin()} sessions bypass the login chain by design
 * (the core discharges the same wiring leg to the CI smoke's real token calls; the console's
 * equivalent leg is discharged here, container-free).
 *
 * <p>Deliberately NOT {@code @ConsoleWebTest}: the stub beans change the merged config, so
 * this class owns the one sanctioned extra context (same trade as the core's second-context
 * suites — an exception made consciously, in writing).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestClientRegistrations.class, ConsoleLoginCallbackTest.LoginStubs.class})
@DisplayName("Login callback (M8a): the production chain mints ROLE_* authorities from the ID token")
class ConsoleLoginCallbackTest {

    @Autowired
    MockMvcTester mvc;

    @TestConfiguration(proxyBeanMethods = false)
    static class LoginStubs {

        /** Token-endpoint stub — the canned response carries the id_token decoded below. */
        @Bean
        OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> tokenClient() {
            return request -> OAuth2AccessTokenResponse.withToken("access-token")
                    .tokenType(OAuth2AccessToken.TokenType.BEARER)
                    .expiresIn(300)
                    .scopes(Set.of("openid"))
                    .additionalParameters(Map.of("id_token", "stub-id-token"))
                    .build();
        }

        /** ID-token decoder stub: ops's claims, exactly as Keycloak's mapper shapes them. */
        @Bean
        JwtDecoderFactory<ClientRegistration> idTokenDecoderFactory() {
            return registration -> token -> Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .issuer("http://localhost:8081/realms/ledger")
                    .subject("ops-subject-uuid")
                    .audience(List.of("ledger-console"))
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(300))
                    .claim("preferred_username", "ops")
                    .claim("realm_access", Map.of("roles",
                            List.of("CONSOLE_OPS", "LEDGER_READ", "LEDGER_ADMIN", "LEDGER_METRICS")))
                    .build();
        }
    }

    @Test
    @DisplayName("callback → token exchange → mapped session — whoami renders the chips")
    void full_login_callback_mints_mapped_authorities() {
        // The authorization request "the browser started with", seeded exactly where the
        // production repository keeps it between the redirect and the callback.
        OAuth2AuthorizationRequest authRequest = OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri(
                        "http://localhost:8081/realms/ledger/protocol/openid-connect/auth")
                .clientId("ledger-console")
                .redirectUri("http://localhost/login/oauth2/code/keycloak")
                .scopes(Set.of("openid"))
                .state("state-123")
                .attributes(Map.of(OAuth2ParameterNames.REGISTRATION_ID, "keycloak"))
                .build();
        MockHttpSession session = new MockHttpSession();
        MockHttpServletRequest seed = new MockHttpServletRequest();
        seed.setSession(session);
        new HttpSessionOAuth2AuthorizationRequestRepository()
                .saveAuthorizationRequest(authRequest, seed, new MockHttpServletResponse());

        MvcTestResult callback = mvc.get()
                .uri("/login/oauth2/code/keycloak")
                .param("code", "stub-code")
                .param("state", "state-123")
                .session(session)
                .exchange();
        assertThat(callback).hasStatus3xxRedirection();

        // The mapped session, not a minted one: chips exist ONLY if the production
        // userAuthoritiesMapper ran during the callback above.
        assertThat(mvc.get().uri("/whoami").session(session))
                .hasStatusOk()
                .bodyText()
                .containsSubsequence(">ops<",
                        ">CONSOLE_OPS<", ">LEDGER_ADMIN<", ">LEDGER_METRICS<", ">LEDGER_READ<")
                .doesNotContain("No ledger roles", "ROLE_");
    }
}
