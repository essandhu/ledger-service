package io.github.essandhu.ledger.adapter.web;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ProblemDetail;

import io.github.essandhu.ledger.application.port.in.AccountNotFound;
import io.github.essandhu.ledger.domain.error.AccountClosed;
import io.github.essandhu.ledger.domain.error.InvalidAccountInput;
import io.github.essandhu.ledger.domain.error.InvalidStatusTransition;
import io.github.essandhu.ledger.domain.model.AccountId;
import io.github.essandhu.ledger.domain.model.AccountStatus;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The advice's exception → ProblemDetail contract, one mapped type at a time (every mapping
 * gets a proof — a mapped behavior with no test is a guarantee without a proof). The type URIs
 * asserted here are pinned constants: they are public API and must never drift.
 */
@DisplayName("RFC 9457 mappings: every advice mapping has a proof")
class LedgerExceptionHandlerTest {

    private static final String PROBLEMS = "https://essandhu.github.io/ledger/problems/";

    private final LedgerExceptionHandler handler = new LedgerExceptionHandler();

    @Test
    @DisplayName("the problem-type base URL is pinned — URI stability is itself a guarantee")
    void problem_type_base_is_pinned() {
        assertThat(ProblemTypes.BASE).isEqualTo(PROBLEMS);
    }

    @Test
    @DisplayName("InvalidStatusTransition → 422 invalid-status-transition")
    void invalid_transition_maps_to_422() {
        ProblemDetail problem = handler.invalidStatusTransition(
                new InvalidStatusTransition(AccountStatus.CLOSED, AccountStatus.ACTIVE));
        assertThat(problem.getStatus()).isEqualTo(422);
        assertThat(problem.getType()).hasToString(PROBLEMS + "invalid-status-transition");
        assertThat(problem.getDetail()).contains("CLOSED").contains("ACTIVE");
    }

    @Test
    @DisplayName("AccountClosed → 422 account-closed")
    void account_closed_maps_to_422() {
        ProblemDetail problem = handler.accountClosed(new AccountClosed("account x is closed"));
        assertThat(problem.getStatus()).isEqualTo(422);
        assertThat(problem.getType()).hasToString(PROBLEMS + "account-closed");
    }

    @Test
    @DisplayName("FieldNotWritable → 422 field-not-writable, detail names field and operation")
    void field_not_writable_maps_to_422() {
        ProblemDetail problem = handler.fieldNotWritable(
                new FieldNotWritable("currency", "PATCH /api/v1/accounts/{id}"));
        assertThat(problem.getStatus()).isEqualTo(422);
        assertThat(problem.getType()).hasToString(PROBLEMS + "field-not-writable");
        assertThat(problem.getDetail()).contains("currency").contains("PATCH");
    }

    @Test
    @DisplayName("AccountNotFound → 404 (path-addressed lookup; M2's payload-unknown-account is 422, not this)")
    void not_found_maps_to_404() {
        ProblemDetail problem = handler.accountNotFound(
                new AccountNotFound(new AccountId(UUID.randomUUID())));
        assertThat(problem.getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("InvalidAccountInput → 400 (domain defense-in-depth below web validation)")
    void invalid_input_maps_to_400() {
        ProblemDetail problem = handler.invalidAccountInput(
                new InvalidAccountInput("account name must not be blank"));
        assertThat(problem.getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("OptimisticLockingFailureException → 409 concurrent-modification")
    void optimistic_lock_maps_to_409() {
        ProblemDetail problem = handler.optimisticLockConflict(
                new OptimisticLockingFailureException("row was updated by another transaction"));
        assertThat(problem.getStatus()).isEqualTo(409);
        assertThat(problem.getType()).hasToString(PROBLEMS + "concurrent-modification");
    }
}
