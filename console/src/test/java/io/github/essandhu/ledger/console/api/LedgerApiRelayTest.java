package io.github.essandhu.ledger.console.api;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restclient.test.MockServerRestClientCustomizer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import io.github.essandhu.ledger.console.support.ConsoleSessions;
import io.github.essandhu.ledger.console.support.ConsoleWebTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The token relay, proven through the PRODUCTION wiring (the M8a lesson, applied): the
 * session seated by {@code oidcLogin()} flows through the manager BEAN into
 * {@code OAuth2ClientHttpRequestInterceptor}, which attaches the Bearer header the mock
 * server asserts — spring-security-test can only seat that token because the manager is the
 * unique bean the argument-resolver infrastructure holds, so this test fails if the relay is
 * rewired around it.
 */
@ConsoleWebTest
@DisplayName("Token relay (M8b): the user's own token rides every API call")
class LedgerApiRelayTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    MockServerRestClientCustomizer customizer;

    MockRestServiceServer server;

    @BeforeEach
    void bindServer() {
        server = customizer.getServer();
        server.reset();
    }

    private static final String EMPTY_PAGE = """
            {"content": [], "page": 0, "size": 20, "totalElements": 0}
            """;

    @Test
    @DisplayName("the accounts call carries Authorization: Bearer — the console holds no credential of its own")
    void bearer_token_attached_by_the_production_interceptor() {
        server.expect(requestTo(startsWith("http://localhost:8080/api/v1/accounts")))
                .andExpect(queryParam("page", "0"))
                .andExpect(queryParam("size", "20"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, startsWith("Bearer ")))
                .andRespond(withSuccess(EMPTY_PAGE, MediaType.APPLICATION_JSON));

        assertThat(mvc.get().uri("/accounts").with(ConsoleSessions.user("ops", "LEDGER_READ")))
                .hasStatusOk();
        server.verify();
    }

    @Autowired
    OAuth2AuthorizedClientRepository authorizedClients;

    @Test
    @DisplayName("an API 401 evicts the stored authorized client — a dead token is never replayed")
    void api_401_evicts_the_stored_authorized_client() throws Exception {
        // A stored client for ops/keycloak, saved through the REAL repository bean — the
        // same one the interceptor's failure handler holds. The principal must be
        // AUTHENTICATED (3-arg token) so save and eviction agree on the service-backed
        // store keyed by principal name.
        MockHttpSession session = new MockHttpSession();
        Authentication ops = new TestingAuthenticationToken("ops", "n/a", "ROLE_LEDGER_READ");
        MockHttpServletRequest save = new MockHttpServletRequest();
        save.setSession(session);
        authorizedClients.saveAuthorizedClient(
                new OAuth2AuthorizedClient(ConsoleSessions.KEYCLOAK, "ops",
                        new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "stale-token",
                                Instant.now(), Instant.now().plusSeconds(300))),
                ops, save, new MockHttpServletResponse());

        server.expect(requestTo(startsWith("http://localhost:8080/api/v1/accounts")))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("{\"type\": \"about:blank\", \"title\": \"Unauthorized\", \"status\": 401}"));

        assertThat(mvc.get().uri("/accounts").session(session)
                .with(ConsoleSessions.user("ops", "LEDGER_READ")))
                .hasStatus(HttpStatus.UNAUTHORIZED);

        // Deleting the setAuthorizationFailureHandler line leaves this client in place.
        MockHttpServletRequest load = new MockHttpServletRequest();
        load.setSession(session);
        assertThat((Object) authorizedClients.loadAuthorizedClient("keycloak", ops, load))
                .isNull();
    }
}
