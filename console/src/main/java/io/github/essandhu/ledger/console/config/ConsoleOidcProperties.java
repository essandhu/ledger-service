package io.github.essandhu.ledger.console.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The console's OIDC provider, described as the ONE thing Boot's property model cannot
 * express: a provider whose URLs differ depending on who is dialling them (M8-stretch,
 * ADR-0007).
 *
 * <p>The browser is redirected to Keycloak on the host's published port; the console JVM
 * reaches the same Keycloak over the compose network. On the host both are
 * {@code http://localhost:8081/realms/ledger} and nothing appears split at all — which is why
 * the same code path serves {@code :console:bootRun} and the container, with no profile fork
 * and no "works only in prod" configuration.
 *
 * <p>This mirrors the split the CORE already needed
 * ({@code LEDGER_JWT_ISSUER_URI} + an in-network {@code jwk-set-uri}), with one difference
 * that is the whole reason this record exists: the resource server can express its split in
 * standard Boot properties, and the OAuth2 <em>client</em> cannot — setting a provider
 * {@code issuer-uri} makes Boot's mapper call {@code ClientRegistrations.fromIssuerLocation()}
 * at startup, and an in-container {@code localhost:8081} is the container's own loopback.
 *
 * @param browserIssuerUri Keycloak's realm URL as the BROWSER must see it. This is also the
 *     {@code iss} the ID token carries (the compose stack pins {@code KC_HOSTNAME} to exactly
 *     this), so it is what the registration's issuer — and therefore Spring Security's
 *     ID-token issuer check — is built from.
 * @param networkIssuerUri Keycloak's realm URL as THIS JVM must dial it: the token exchange,
 *     the JWKS fetch, and the userinfo call never leave the machine the console runs on.
 * @param clientId the confidential client the realm file defines ({@code ledger-console}).
 * @param clientSecret its secret; a dev value in this stack, an injected one anywhere real.
 */
@ConfigurationProperties("ledger.console.oidc")
public record ConsoleOidcProperties(
        String browserIssuerUri, String networkIssuerUri, String clientId, String clientSecret) {
}
