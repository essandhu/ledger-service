package io.github.essandhu.ledger.console.config;

import java.util.Collection;
import java.util.Map;
import java.util.stream.Stream;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.authority.mapping.GrantedAuthoritiesMapper;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;

/**
 * Maps Keycloak realm roles from the OIDC ID token to {@code ROLE_*} authorities — the
 * console-side mirror of the core's {@code LedgerRealmRoleConverter} (same claim shape, same
 * defensive parsing, same prefix-filter discipline).
 *
 * <p>Keycloak only puts {@code realm_access} in the ACCESS token by default; the
 * {@code ledger-console} client carries a per-client protocol mapper that adds it to the ID
 * token too (ADR-0007) — the alternative, parsing the access-token string client-side, would
 * re-implement resource-server validation in an app that has none. {@code CONSOLE_*} roles
 * pass the filter alongside {@code LEDGER_*} so the whoami page can show a composite bundle
 * (e.g. {@code CONSOLE_OPS}) next to the expanded roles it grants; the API itself never
 * checks {@code CONSOLE_*}.
 *
 * <p>A malformed or missing claim yields no authorities — authenticated but role-less, never
 * an exception. Public (not package-private) deliberately: tests route claims through this
 * production mapper rather than granting authorities directly.
 */
public class ConsoleRealmRoleMapper implements GrantedAuthoritiesMapper {

    @Override
    public Collection<? extends GrantedAuthority> mapAuthorities(
            Collection<? extends GrantedAuthority> authorities) {
        return authorities.stream()
                .filter(OidcUserAuthority.class::isInstance)
                .map(OidcUserAuthority.class::cast)
                .flatMap(oidc -> realmRoles(oidc.getIdToken()))
                .distinct()
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
    }

    private static Stream<String> realmRoles(OidcIdToken idToken) {
        if (idToken != null
                && idToken.getClaims().get("realm_access") instanceof Map<?, ?> realmAccess
                && realmAccess.get("roles") instanceof Collection<?> roles) {
            return roles.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .filter(role -> role.startsWith("LEDGER_") || role.startsWith("CONSOLE_"));
        }
        return Stream.empty();
    }
}
