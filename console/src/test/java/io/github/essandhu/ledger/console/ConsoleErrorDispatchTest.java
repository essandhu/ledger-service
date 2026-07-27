package io.github.essandhu.ledger.console;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import io.github.essandhu.ledger.console.support.TestClientRegistrations;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the ERROR-dispatch permit in {@code ConsoleSecurityConfig}: a permitted-but-missing
 * asset must 404 plainly, not bounce its error rendering to Keycloak with a
 * {@code ;jsessionid} in the Location. MockMvc never performs the container's ERROR
 * dispatch, so this is the console's one servlet-container test — RANDOM_PORT is a second
 * deliberate context (documented trade, same as {@code ConsoleLoginCallbackTest}). The
 * plain JDK client with {@code Redirect.NEVER} asserts first hops only; a
 * redirect-following client would walk into whatever Keycloak is or isn't running.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestClientRegistrations.class)
@DisplayName("Error dispatch (M8a): permitted-but-missing assets 404 plainly, no Keycloak bounce")
class ConsoleErrorDispatchTest {

    @LocalServerPort
    int port;

    private final HttpClient client =
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("a missing favicon 404s — the error render must not re-demand login")
    void missing_favicon_is_a_plain_404() throws Exception {
        assertThat(get("/favicon.ico").statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("a mistyped stylesheet path 404s, not 302s")
    void missing_css_is_a_plain_404() throws Exception {
        assertThat(get("/css/nope.css").statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("control: protected pages still bounce to login in a real container")
    void protected_pages_still_redirect() throws Exception {
        HttpResponse<String> response = get("/whoami");

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("Location").orElseThrow())
                .endsWith("/oauth2/authorization/keycloak");
    }
}
