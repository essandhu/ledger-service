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
import io.github.essandhu.ledger.application.port.in.EntryNotFound;
import io.github.essandhu.ledger.application.port.in.GetAccountQuery;
import io.github.essandhu.ledger.application.port.in.GetBalanceQuery;
import io.github.essandhu.ledger.application.port.in.GetJournalEntryQuery;
import io.github.essandhu.ledger.application.port.in.PostJournalEntryUseCase;
import io.github.essandhu.ledger.application.port.in.PostJournalEntryUseCase.PostEntryCommand;
import io.github.essandhu.ledger.application.port.in.GetStatementQuery;
import io.github.essandhu.ledger.application.port.in.ReverseEntryUseCase;
import io.github.essandhu.ledger.application.port.in.ReverseEntryUseCase.ReverseCommand;
import io.github.essandhu.ledger.application.port.in.StatementFilter;
import io.github.essandhu.ledger.application.port.in.StatementSpec;
import io.github.essandhu.ledger.application.port.in.TransferFundsUseCase;
import io.github.essandhu.ledger.application.port.in.TransferFundsUseCase.TransferCommand;
import io.github.essandhu.ledger.domain.error.UnknownPostingAccount;
import io.github.essandhu.ledger.domain.model.AccountId;
import io.github.essandhu.ledger.domain.model.AccountType;
import io.github.essandhu.ledger.domain.model.CurrencyCode;
import io.github.essandhu.ledger.domain.model.EntryDraft;
import io.github.essandhu.ledger.domain.model.EntryId;
import io.github.essandhu.ledger.domain.model.Money;
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

    // The three M2 write ports resolve to the @Primary metered decorator (UseCaseConfig) —
    // deliberately probed THROUGH it: the denial must still come from the @PreAuthorize on the
    // delegate, proving the decorator opens no bypass around the security proxy.
    @Autowired
    private PostJournalEntryUseCase postEntry;

    @Autowired
    private TransferFundsUseCase transferFunds;

    @Autowired
    private ReverseEntryUseCase reverseEntry;

    @Autowired
    private GetJournalEntryQuery getEntry;

    @Autowired
    private GetBalanceQuery getBalance;

    @Autowired
    private GetStatementQuery getStatement;

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

    @Test
    @DisplayName("M2 write ports deny wrong roles at the method boundary — probed through the metered decorator")
    void posting_ports_deny_wrong_roles_at_the_method_boundary() {
        authenticateWithRoles("LEDGER_ADMIN", "LEDGER_READ"); // every role EXCEPT the write role
        assertThatThrownBy(() -> postEntry.postEntry(balancedProbeCommand()))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> transferFunds.transfer(transferProbeCommand()))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> reverseEntry.reverse(new ReverseCommand(
                new EntryId(UUID.randomUUID()), null, "method-security-test",
                UUID.randomUUID().toString())))
                .isInstanceOf(AccessDeniedException.class);

        authenticateWithRoles("LEDGER_WRITE", "LEDGER_ADMIN"); // wrong roles for a read
        assertThatThrownBy(() -> getEntry.byId(new EntryId(UUID.randomUUID())))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("M2 ports: the right role reaches domain logic — domain errors, not access denials")
    void posting_ports_right_role_reaches_domain_logic() {
        authenticateWithRoles("LEDGER_WRITE");
        // UnknownPostingAccount / EntryNotFound (not AccessDeniedException) prove authorization
        // passed and the method body executed all the way to the lock-and-resolve step.
        assertThatThrownBy(() -> postEntry.postEntry(balancedProbeCommand()))
                .isInstanceOf(UnknownPostingAccount.class);
        assertThatThrownBy(() -> transferFunds.transfer(transferProbeCommand()))
                .isInstanceOf(UnknownPostingAccount.class);
        assertThatThrownBy(() -> reverseEntry.reverse(new ReverseCommand(
                new EntryId(UUID.randomUUID()), null, "method-security-test",
                UUID.randomUUID().toString())))
                .isInstanceOf(EntryNotFound.class);

        authenticateWithRoles("LEDGER_READ");
        assertThatThrownBy(() -> getEntry.byId(new EntryId(UUID.randomUUID())))
                .isInstanceOf(EntryNotFound.class);
    }

    @Test
    @DisplayName("M3 read ports deny wrong roles at the method boundary")
    void balance_ports_deny_wrong_roles_at_the_method_boundary() {
        authenticateWithRoles("LEDGER_WRITE", "LEDGER_ADMIN"); // every role EXCEPT the read role
        AccountId anyId = new AccountId(UUID.randomUUID());
        assertThatThrownBy(() -> getBalance.current(anyId))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> getBalance.asOf(anyId, java.time.Instant.EPOCH))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> getStatement.statement(anyId, StatementFilter.unbounded(),
                StatementSpec.firstPage(1)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("M3 read ports: the right role reaches domain logic — 404s, not access denials")
    void balance_ports_right_role_reaches_domain_logic() {
        authenticateWithRoles("LEDGER_READ");
        AccountId unknown = new AccountId(UUID.randomUUID());
        assertThatThrownBy(() -> getBalance.current(unknown))
                .isInstanceOf(AccountNotFound.class);
        assertThatThrownBy(() -> getBalance.asOf(unknown, java.time.Instant.EPOCH))
                .isInstanceOf(AccountNotFound.class);
        assertThatThrownBy(() -> getStatement.statement(unknown, StatementFilter.unbounded(),
                StatementSpec.firstPage(1)))
                .isInstanceOf(AccountNotFound.class);
    }

    /** A balanced two-leg command on accounts that do not exist — valid past every pure check. */
    private static PostEntryCommand balancedProbeCommand() {
        CurrencyCode eur = new CurrencyCode("EUR");
        return new PostEntryCommand("method-security-probe", List.of(
                new EntryDraft.Leg(new AccountId(UUID.randomUUID()), Money.of(100, eur)),
                new EntryDraft.Leg(new AccountId(UUID.randomUUID()), Money.of(-100, eur))),
                "method-security-test", UUID.randomUUID().toString());
    }

    private static TransferCommand transferProbeCommand() {
        return new TransferCommand(new AccountId(UUID.randomUUID()),
                new AccountId(UUID.randomUUID()),
                Money.of(100, new CurrencyCode("EUR")), null, "method-security-test",
                UUID.randomUUID().toString());
    }
}
