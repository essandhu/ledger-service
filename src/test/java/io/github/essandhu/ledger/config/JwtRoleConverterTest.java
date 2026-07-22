package io.github.essandhu.ledger.config;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The claim-to-authority mapping (PLAN §7): Keycloak realm roles arrive under
 * {@code realm_access.roles}; only {@code LEDGER_*} roles become authorities, prefixed
 * {@code ROLE_}. This unit test is one leg of the I13 proof triangulation — the integration
 * matrix routes its minted JWTs through this same converter, and the CI smoke exercises the
 * real issuer end-to-end.
 */
@DisplayName("I13: Keycloak realm_access.roles → ROLE_LEDGER_* authorities")
class JwtRoleConverterTest {

    private final LedgerRealmRoleConverter converter = new LedgerRealmRoleConverter();

    private static Jwt jwtWithClaim(String name, Object value) {
        return Jwt.withTokenValue("token").header("alg", "none").claim(name, value).build();
    }

    @Test
    @DisplayName("maps LEDGER_* realm roles and ignores everything else")
    void maps_ledger_roles_only() {
        Jwt jwt = jwtWithClaim("realm_access",
                Map.of("roles", List.of("LEDGER_ADMIN", "LEDGER_READ", "offline_access", "uma_authorization")));
        Collection<GrantedAuthority> authorities = converter.convert(jwt);
        assertThat(authorities).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_LEDGER_ADMIN", "ROLE_LEDGER_READ");
    }

    @Test
    @DisplayName("no realm_access claim → no authorities (authenticated, role-less principal)")
    void missing_claim_yields_no_authorities() {
        assertThat(converter.convert(jwtWithClaim("sub", "someone"))).isEmpty();
    }

    @Test
    @DisplayName("malformed realm_access shapes are ignored, not errors")
    void malformed_claim_shapes_are_ignored() {
        assertThat(converter.convert(jwtWithClaim("realm_access", "not-a-map"))).isEmpty();
        assertThat(converter.convert(jwtWithClaim("realm_access", Map.of("roles", "not-a-list")))).isEmpty();
        assertThat(converter.convert(jwtWithClaim("realm_access", Map.of("other", List.of())))).isEmpty();
    }
}
