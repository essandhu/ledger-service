package io.github.essandhu.ledger.config;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import io.github.essandhu.ledger.application.port.in.AccountNotFound;
import io.github.essandhu.ledger.application.port.in.CreateAccountUseCase;
import io.github.essandhu.ledger.application.port.in.CreateAccountUseCase.CreateAccountCommand;
import io.github.essandhu.ledger.application.port.in.GetAccountQuery;
import io.github.essandhu.ledger.domain.model.AccountId;
import io.github.essandhu.ledger.domain.model.AccountType;
import io.github.essandhu.ledger.domain.model.CurrencyCode;
import io.github.essandhu.ledger.support.LedgerIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * I13's SECOND layer proven in isolation: {@code @PreAuthorize} on the use-case beans must
 * deny on its own, with no filter chain in front. Every HTTP-level matrix cell is decided by
 * the request matchers before the service is reached, so without this test the method layer
 * could be silently disabled (annotation dropped, {@code @EnableMethodSecurity} removed) and
 * the whole suite would stay green — defense in depth demands each layer have a test where it
 * is the only defense.
 */
@LedgerIntegrationTest
@DisplayName("I13 (layer 2): @PreAuthorize denies on the use-case beans directly")
class MethodSecurityIntegrationTest {

    @Autowired
    private GetAccountQuery getAccount;

    @Autowired
    private CreateAccountUseCase createAccount;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateWithRoles(String... roles) {
        List<SimpleGrantedAuthority> authorities = java.util.Arrays.stream(roles)
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("method-security-test", "n/a", authorities));
    }

    @Test
    @DisplayName("wrong role is denied at the method boundary — no filter chain involved")
    void wrong_role_is_denied_at_the_method_boundary() {
        authenticateWithRoles("LEDGER_ADMIN"); // wrong role for a read
        AccountId anyId = new AccountId(UUID.randomUUID());
        assertThatThrownBy(() -> getAccount.byId(anyId))
                .isInstanceOf(AccessDeniedException.class);

        authenticateWithRoles("LEDGER_READ", "LEDGER_WRITE"); // wrong roles for admin work
        CreateAccountCommand command = new CreateAccountCommand(
                "method-security-probe-" + UUID.randomUUID(), new CurrencyCode("EUR"),
                AccountType.ASSET, false);
        assertThatThrownBy(() -> createAccount.create(command))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("the right role passes the method boundary and reaches domain logic")
    void right_role_reaches_domain_logic() {
        authenticateWithRoles("LEDGER_READ");
        AccountId unknown = new AccountId(UUID.randomUUID());
        // AccountNotFound (not AccessDeniedException) proves authorization passed and the
        // method body actually executed.
        assertThatThrownBy(() -> getAccount.byId(unknown)).isInstanceOf(AccountNotFound.class);

        authenticateWithRoles("LEDGER_ADMIN");
        assertThat(createAccount.create(new CreateAccountCommand(
                "method-security-probe-" + UUID.randomUUID(), new CurrencyCode("EUR"),
                AccountType.ASSET, false)).id()).isNotNull();
    }
}
