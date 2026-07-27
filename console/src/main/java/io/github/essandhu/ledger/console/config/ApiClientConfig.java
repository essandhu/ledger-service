package io.github.essandhu.ledger.console.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.restclient.autoconfigure.RestClientBuilderConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.client.OAuth2ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;

/**
 * The token relay (ADR-0007): a hand-built {@link RestClient} whose
 * {@link OAuth2ClientHttpRequestInterceptor} attaches the logged-in user's access token to
 * every API call — with silent refresh via the manager's default refresh_token provider, so
 * a browsing session outlives the realm's 900-second access tokens.
 *
 * <p>Wiring constraints that are load-bearing, not style:
 * <ul>
 * <li>The manager is a {@code @Bean} — Security's argument-resolver infrastructure and
 * spring-security-test's {@code oidcLogin()} seam both discover the manager bean, so the
 * relay tests exercise THIS wiring, not a lookalike. (Erratum to ADR-0007 recorded at M8b:
 * Security 7.1's registrar would auto-register an equivalent manager; defining the bean
 * keeps the wiring explicit and suppresses it.)</li>
 * <li>The registration id resolves from the AUTHENTICATION, not the default per-request
 * attribute: the default resolver returns null when the attribute is forgotten and the
 * request silently goes out unauthenticated — an entire failure class removed.</li>
 * <li>The failure handler evicts the stored authorized client when the API answers 401, so
 * a Keycloak-side revocation forces re-authorization instead of replaying a dead token.</li>
 * <li>The builder is configured through Boot's {@link RestClientBuilderConfigurer} so the
 * client gets Boot's message converters (Jackson with java.time — the API's Instant fields)
 * and so tests can bind a mock server through the same path.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
class ApiClientConfig {

    @Bean
    OAuth2AuthorizedClientManager authorizedClientManager(
            ClientRegistrationRepository clientRegistrations,
            OAuth2AuthorizedClientRepository authorizedClients) {
        // Default providers: authorization_code + refresh_token + client_credentials.
        return new DefaultOAuth2AuthorizedClientManager(clientRegistrations, authorizedClients);
    }

    @Bean
    RestClient.Builder ledgerApiBuilder(
            RestClientBuilderConfigurer configurer,
            OAuth2AuthorizedClientManager authorizedClientManager,
            OAuth2AuthorizedClientRepository authorizedClients,
            @Value("${ledger.api.base-url}") String apiBaseUrl) {
        OAuth2ClientHttpRequestInterceptor relay =
                new OAuth2ClientHttpRequestInterceptor(authorizedClientManager);
        relay.setClientRegistrationIdResolver(request ->
                SecurityContextHolder.getContext().getAuthentication()
                        instanceof OAuth2AuthenticationToken token
                                ? token.getAuthorizedClientRegistrationId()
                                : null);
        relay.setAuthorizationFailureHandler(
                OAuth2ClientHttpRequestInterceptor.authorizationFailureHandler(authorizedClients));
        return configurer.configure(RestClient.builder())
                .baseUrl(apiBaseUrl)
                .requestInterceptor(relay);
    }
}
