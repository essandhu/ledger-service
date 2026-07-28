package io.github.essandhu.ledger.console.config;

import java.util.Map;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.StandardClaimNames;

/**
 * The console's OIDC client registration, assembled by hand from
 * {@link ConsoleOidcProperties} instead of discovered from the provider (M8-stretch,
 * ADR-0007). Defining this bean is what makes Boot's property-driven registration mapping
 * back off ({@code @ConditionalOnMissingBean}) — and with it the eager
 * {@code ClientRegistrations.fromIssuerLocation()} call that made the console
 * un-containerizable and un-loadable without a live Keycloak.
 *
 * <p>What discovery bought and what replaces it, endpoint by endpoint:
 * <ul>
 * <li><b>authorization + end_session</b> — the two the BROWSER is sent to, so they are built
 * from the browser-facing URL. {@code end_session_endpoint} lives in the provider's
 * configuration metadata because that is the only place
 * {@code OidcClientInitiatedLogoutSuccessHandler} looks; without it RP-initiated logout
 * silently degrades to a local-only logout, which is the exact failure the pre-research
 * predicted.</li>
 * <li><b>token + JWKS</b> — dialled by this JVM, so they are built from the in-network URL.
 * In compose that is {@code http://keycloak:8080}, the same hop the core's resource server
 * already takes for its JWKS.</li>
 * <li><b>userinfo</b> — deliberately ABSENT, which discovery gave us no way to say. The
 * console sources the user entirely from the ID token: {@code ConsoleRealmRoleMapper} reads
 * {@code realm_access} off {@code getIdToken()} by design (ADR-0007 decision 6 rejected the
 * userinfo path for roles outright), and {@code preferred_username} rides the profile scope
 * into the same token. Configuring the endpoint would make {@code OidcUserService} fetch it
 * on every login and merge claims nothing reads — one more network leg on the login path,
 * with one more way to fail, for no data. Omitting the URI is what suppresses the call;
 * {@code userNameAttributeName} below is still honoured (it needs no endpoint), and without
 * it the principal would fall back to OIDC's {@code sub} UUID.</li>
 * <li><b>issuer</b> — set to the BROWSER-facing URL, because that is what Keycloak stamps
 * into {@code iss} (the stack pins {@code KC_HOSTNAME}). This is a correction to the recipe
 * ADR-0007 pre-researched, which assumed the ID-token issuer check had to be forgone:
 * {@code OidcIdTokenValidator} compares {@code iss} against the registration's
 * {@code ProviderDetails.getIssuerUri()} — NOT against discovery metadata — so a hand-built
 * registration keeps the check fully armed. Dropping the issuer here would disarm it
 * silently, which is why it is set rather than omitted.</li>
 * </ul>
 *
 * <p>The cost, on record: Keycloak's {@code /protocol/openid-connect/*} URL layout is
 * hardcoded below rather than read from the provider. That is the honest price of not asking
 * a provider we cannot reach at the URL the browser uses, and it is one realm file away from
 * being wrong loudly rather than subtly — every one of these endpoints is exercised by the
 * browser lane against the real Keycloak.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ConsoleOidcProperties.class)
public class ConsoleOidcConfig {

    /**
     * Not a property: this id is spelled out in the realm's {@code redirectUris}, in the
     * login-bounce URL every test asserts, and in the back-channel logout URL Keycloak posts
     * to. It varies with nothing.
     */
    public static final String REGISTRATION_ID = "keycloak";

    @Bean
    ClientRegistrationRepository clientRegistrationRepository(ConsoleOidcProperties properties) {
        return new InMemoryClientRegistrationRepository(keycloak(properties));
    }

    /** The production registration — public so tests build the real thing, never a lookalike. */
    public static ClientRegistration keycloak(ConsoleOidcProperties properties) {
        String browser = withoutTrailingSlash(properties.browserIssuerUri());
        String network = withoutTrailingSlash(properties.networkIssuerUri());
        return ClientRegistration.withRegistrationId(REGISTRATION_ID)
                .clientId(properties.clientId())
                .clientSecret(properties.clientSecret())
                // Explicit, not defaulted: the builder would pick client_secret_basic anyway,
                // but this is a real network leg against a real provider — a silent default is
                // not the place to leave it.
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                // Resolved per request, so the browser's own host answers back — the realm
                // registers exactly http://localhost:8090/login/oauth2/code/keycloak.
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                // openid is REQUIRED and not defaulted: without it login silently downgrades to
                // plain OAuth2 — no ID token, so no role chips (the roles ride the ID token by
                // realm mapper) and no id_token_hint for RP-initiated logout. profile carries
                // preferred_username.
                .scope("openid", "profile")
                // Without this the principal name is OIDC's default `sub`, a UUID.
                .userNameAttributeName(StandardClaimNames.PREFERRED_USERNAME)
                .issuerUri(browser)
                .authorizationUri(browser + "/protocol/openid-connect/auth")
                .tokenUri(network + "/protocol/openid-connect/token")
                .jwkSetUri(network + "/protocol/openid-connect/certs")
                .providerConfigurationMetadata(
                        Map.of("end_session_endpoint", endSessionEndpoint(browser)))
                .build();
    }

    /** Where sign-out sends the browser — browser-facing by definition (ADR-0007). */
    public static String endSessionEndpoint(String browserIssuerUri) {
        return withoutTrailingSlash(browserIssuerUri) + "/protocol/openid-connect/logout";
    }

    private static String withoutTrailingSlash(String uri) {
        return uri.endsWith("/") ? uri.substring(0, uri.length() - 1) : uri;
    }
}
