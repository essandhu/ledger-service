package io.github.essandhu.ledger.config;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Keycloak realm roles ({@code realm_access.roles}) → Spring authorities. Only
 * {@code LEDGER_*} roles are mapped ({@code ROLE_}-prefixed for {@code hasRole}); Keycloak's
 * built-ins (offline_access, uma_authorization, ...) and malformed claim shapes yield nothing —
 * an authenticated, role-less principal, which the I13 matrix pins to 403.
 *
 * <p>Public deliberately: the integration matrix routes its minted JWTs through this exact
 * class, so the claim mapping is on the tested path (the test-type rules triangulation — unit
 * test for the mapping, matrix for access decisions, CI smoke for the real issuer).
 */
public class LedgerRealmRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        if (jwt.getClaims().get("realm_access") instanceof Map<?, ?> realmAccess
                && realmAccess.get("roles") instanceof Collection<?> roles) {
            return roles.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .filter(role -> role.startsWith("LEDGER_"))
                    .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                    .toList();
        }
        return List.of();
    }
}
