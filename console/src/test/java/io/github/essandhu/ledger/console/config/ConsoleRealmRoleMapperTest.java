package io.github.essandhu.ledger.console.config;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The console-side mirror of the core's realm-role extraction: same claim shape, same
 * defensive posture. A user whose token cannot be parsed must end up authenticated but
 * role-less — never an exception, never a stray authority.
 */
@DisplayName("ConsoleRealmRoleMapper (M8a): realm_access.roles → ROLE_* authorities")
class ConsoleRealmRoleMapperTest {

    private final ConsoleRealmRoleMapper mapper = new ConsoleRealmRoleMapper();

    private static OidcUserAuthority oidcAuthority(Object realmAccess) {
        OidcIdToken idToken = OidcIdToken.withTokenValue("id-token")
                .claim("sub", "user")
                .claim("realm_access", realmAccess)
                .build();
        return new OidcUserAuthority(idToken);
    }

    @Test
    @DisplayName("LEDGER_* and CONSOLE_* realm roles map to ROLE_-prefixed authorities")
    void maps_ledger_and_console_roles() {
        var mapped = mapper.mapAuthorities(List.of(oidcAuthority(
                Map.of("roles", List.of("CONSOLE_OPS", "LEDGER_READ", "LEDGER_ADMIN")))));

        assertThat(mapped).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder(
                        "ROLE_CONSOLE_OPS", "ROLE_LEDGER_READ", "ROLE_LEDGER_ADMIN");
    }

    @Test
    @DisplayName("foreign realm roles (Keycloak defaults) are filtered out, not prefixed")
    void filters_foreign_roles() {
        var mapped = mapper.mapAuthorities(List.of(oidcAuthority(Map.of("roles",
                List.of("offline_access", "uma_authorization", "default-roles-ledger",
                        "LEDGER_READ")))));

        assertThat(mapped).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_LEDGER_READ");
    }

    @Test
    @DisplayName("a missing realm_access claim yields no authorities — not an exception")
    void missing_claim_yields_nothing() {
        OidcIdToken bare = OidcIdToken.withTokenValue("id-token").claim("sub", "user").build();

        assertThat(mapper.mapAuthorities(List.of(new OidcUserAuthority(bare)))).isEmpty();
    }

    @Test
    @DisplayName("malformed shapes (scalar claim, scalar roles, non-string entries) all yield nothing")
    void malformed_claim_yields_nothing() {
        assertThat(mapper.mapAuthorities(List.of(oidcAuthority("junk")))).isEmpty();
        assertThat(mapper.mapAuthorities(List.of(oidcAuthority(Map.of("roles", "junk"))))).isEmpty();
        assertThat(mapper.mapAuthorities(List.of(oidcAuthority(
                Map.of("roles", List.of(42, true)))))).isEmpty();
    }

    @Test
    @DisplayName("non-OIDC authorities are dropped: only ID-token roles reach the session")
    void non_oidc_authorities_are_dropped() {
        assertThat(mapper.mapAuthorities(List.of(new SimpleGrantedAuthority("ROLE_LEDGER_ADMIN"))))
                .isEmpty();
    }

    @Test
    @DisplayName("duplicate roles across authorities collapse to one")
    void duplicates_collapse() {
        var duplicated = List.of(
                oidcAuthority(Map.of("roles", List.of("LEDGER_READ"))),
                oidcAuthority(Map.of("roles", List.of("LEDGER_READ"))));

        assertThat(mapper.mapAuthorities(duplicated))
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_LEDGER_READ");
    }
}
