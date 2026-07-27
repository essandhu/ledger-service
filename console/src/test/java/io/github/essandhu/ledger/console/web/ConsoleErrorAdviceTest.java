package io.github.essandhu.ledger.console.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.client.ClientAuthorizationException;
import org.springframework.security.oauth2.client.ClientAuthorizationRequiredException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.ui.ConcurrentModel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The relay-failure branch, unit-tested the core's way (the advice instantiated directly —
 * the {@code LedgerExceptionHandlerTest} pattern): a failed refresh renders as the session
 * problem it is, while {@code ClientAuthorizationRequiredException} must keep propagating or
 * Security's redirect-to-login filter never sees it.
 */
@DisplayName("ConsoleErrorAdvice (M8b): relay authorization failures")
class ConsoleErrorAdviceTest {

    private final ConsoleErrorAdvice advice = new ConsoleErrorAdvice();

    private static ClientAuthorizationException refreshFailure() {
        return new ClientAuthorizationException(
                new OAuth2Error("invalid_grant", "Session not active", null), "keycloak");
    }

    @Test
    @DisplayName("a dead refresh token renders the session-expired page with a mirrored 401")
    void refresh_failure_renders_session_expired() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        ConcurrentModel model = new ConcurrentModel();

        String view = advice.authorizationFailed(
                refreshFailure(), new MockHttpServletRequest(), response, model);

        assertThat(view).isEqualTo("error");
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(model.getAttribute("heading")).isEqualTo("Console session expired");
        assertThat((String) model.getAttribute("detail")).contains("Sign out and back in");
    }

    @Test
    @DisplayName("inside an htmx swap the same failure arrives as the fragment")
    void refresh_failure_renders_fragment_for_htmx() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("HX-Request", "true");

        String view = advice.authorizationFailed(
                refreshFailure(), request, new MockHttpServletResponse(), new ConcurrentModel());

        assertThat(view).isEqualTo("error :: problem");
    }

    @Test
    @DisplayName("ClientAuthorizationRequiredException keeps propagating — the login redirect depends on it")
    void authorization_required_is_rethrown() {
        assertThatThrownBy(() -> advice.authorizationFailed(
                new ClientAuthorizationRequiredException("keycloak"),
                new MockHttpServletRequest(), new MockHttpServletResponse(),
                new ConcurrentModel()))
                .isInstanceOf(ClientAuthorizationRequiredException.class);
    }
}
