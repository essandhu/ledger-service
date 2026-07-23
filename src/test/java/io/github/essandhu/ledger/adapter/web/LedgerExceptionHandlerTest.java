package io.github.essandhu.ledger.adapter.web;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ProblemDetail;

import io.github.essandhu.ledger.application.port.in.AccountNotFound;
import io.github.essandhu.ledger.application.port.in.EntryNotFound;
import io.github.essandhu.ledger.domain.error.AccountBalanceNotZero;
import io.github.essandhu.ledger.domain.error.AccountClosed;
import io.github.essandhu.ledger.domain.error.AccountFrozen;
import io.github.essandhu.ledger.domain.error.AmountOverflow;
import io.github.essandhu.ledger.domain.error.CurrencyMismatch;
import io.github.essandhu.ledger.domain.error.EntryAlreadyReversed;
import io.github.essandhu.ledger.domain.error.InvalidAccountInput;
import io.github.essandhu.ledger.domain.error.InvalidEntryInput;
import io.github.essandhu.ledger.domain.error.InvalidStatusTransition;
import io.github.essandhu.ledger.domain.error.OverdraftViolation;
import io.github.essandhu.ledger.domain.error.TooFewPostings;
import io.github.essandhu.ledger.domain.error.UnbalancedEntry;
import io.github.essandhu.ledger.domain.error.UnknownPostingAccount;
import io.github.essandhu.ledger.domain.error.ZeroAmountPosting;
import io.github.essandhu.ledger.domain.model.AccountId;
import io.github.essandhu.ledger.domain.model.AccountStatus;
import io.github.essandhu.ledger.domain.model.CurrencyCode;
import io.github.essandhu.ledger.domain.model.EntryId;

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

    // ── M2 posting mappings (PLAN §5) ──────────────────────────────────────────────────────
    // The slugs below double as the ledger.posting.rejected reason vocabulary (PLAN §8), so
    // each pin here also pins a metric tag.

    @Test
    @DisplayName("UnbalancedEntry → 422 unbalanced-entry, machine-readable per-currency residuals")
    void unbalanced_entry_maps_to_422() {
        ProblemDetail problem = handler.unbalancedEntry(
                new UnbalancedEntry(Map.of(new CurrencyCode("EUR"), 1L)));
        assertThat(problem.getStatus()).isEqualTo(422);
        assertThat(problem.getType()).hasToString(PROBLEMS + "unbalanced-entry");
        // I1 is exact integer equality — the residual is reported to the minor unit.
        assertThat(problem.getProperties()).containsEntry("residuals", Map.of("EUR", 1L));
    }

    @Test
    @DisplayName("TooFewPostings → 422 too-few-postings")
    void too_few_postings_maps_to_422() {
        ProblemDetail problem = handler.tooFewPostings(new TooFewPostings(1));
        assertThat(problem.getStatus()).isEqualTo(422);
        assertThat(problem.getType()).hasToString(PROBLEMS + "too-few-postings");
    }

    @Test
    @DisplayName("ZeroAmountPosting → 422 zero-amount-posting, naming the offending account")
    void zero_amount_posting_maps_to_422() {
        AccountId accountId = new AccountId(UUID.randomUUID());
        ProblemDetail problem = handler.zeroAmountPosting(new ZeroAmountPosting(accountId));
        assertThat(problem.getStatus()).isEqualTo(422);
        assertThat(problem.getType()).hasToString(PROBLEMS + "zero-amount-posting");
        assertThat(problem.getProperties()).containsEntry("accountId", accountId.value());
    }

    @Test
    @DisplayName("CurrencyMismatch → 422 currency-mismatch, detail names both currencies")
    void currency_mismatch_maps_to_422() {
        ProblemDetail problem = handler.currencyMismatch(
                new CurrencyMismatch(new CurrencyCode("EUR"), new CurrencyCode("USD")));
        assertThat(problem.getStatus()).isEqualTo(422);
        assertThat(problem.getType()).hasToString(PROBLEMS + "currency-mismatch");
        assertThat(problem.getDetail()).contains("EUR").contains("USD");
    }

    @Test
    @DisplayName("AmountOverflow → 422 amount-overflow (checked arithmetic surfaces as rejection, not 500)")
    void amount_overflow_maps_to_422() {
        ProblemDetail problem = handler.amountOverflow(
                new AmountOverflow("entry amounts overflow 64-bit minor units"));
        assertThat(problem.getStatus()).isEqualTo(422);
        assertThat(problem.getType()).hasToString(PROBLEMS + "amount-overflow");
    }

    @Test
    @DisplayName("OverdraftViolation → 422 overdraft, naming the offending account")
    void overdraft_maps_to_422() {
        AccountId accountId = new AccountId(UUID.randomUUID());
        ProblemDetail problem = handler.overdraftViolation(new OverdraftViolation(accountId, -5));
        assertThat(problem.getStatus()).isEqualTo(422);
        assertThat(problem.getType()).hasToString(PROBLEMS + "overdraft");
        assertThat(problem.getProperties()).containsEntry("accountId", accountId.value());
        assertThat(problem.getDetail()).contains("-5");
    }

    @Test
    @DisplayName("AccountFrozen → 422 account-frozen (distinct from account-closed: it is reversible)")
    void account_frozen_maps_to_422() {
        ProblemDetail problem = handler.accountFrozen(new AccountFrozen("account x is frozen"));
        assertThat(problem.getStatus()).isEqualTo(422);
        assertThat(problem.getType()).hasToString(PROBLEMS + "account-frozen");
    }

    @Test
    @DisplayName("UnknownPostingAccount → 422 unknown-account — the promised payload-side split from the 404")
    void unknown_posting_account_maps_to_422() {
        AccountId first = new AccountId(UUID.randomUUID());
        AccountId second = new AccountId(UUID.randomUUID());
        ProblemDetail problem = handler.unknownPostingAccount(
                new UnknownPostingAccount(Set.of(first, second)));
        assertThat(problem.getStatus()).isEqualTo(422);
        assertThat(problem.getType()).hasToString(PROBLEMS + "unknown-account");
        // Every unknown id, sorted for determinism — one round-trip reports the whole damage.
        assertThat(problem.getProperties()).containsEntry("accountIds",
                Stream.of(first, second).map(id -> id.value().toString()).sorted().toList());
    }

    @Test
    @DisplayName("EntryAlreadyReversed → 422 entry-already-reversed")
    void entry_already_reversed_maps_to_422() {
        ProblemDetail problem = handler.entryAlreadyReversed(
                new EntryAlreadyReversed(new EntryId(UUID.randomUUID())));
        assertThat(problem.getStatus()).isEqualTo(422);
        assertThat(problem.getType()).hasToString(PROBLEMS + "entry-already-reversed");
    }

    @Test
    @DisplayName("AccountBalanceNotZero → 422 account-balance-not-zero, naming the offending account")
    void account_balance_not_zero_maps_to_422() {
        AccountId accountId = new AccountId(UUID.randomUUID());
        ProblemDetail problem = handler.accountBalanceNotZero(
                new AccountBalanceNotZero(accountId, 7));
        assertThat(problem.getStatus()).isEqualTo(422);
        assertThat(problem.getType()).hasToString(PROBLEMS + "account-balance-not-zero");
        assertThat(problem.getProperties()).containsEntry("accountId", accountId.value());
    }

    @Test
    @DisplayName("EntryNotFound → 404 (path-addressed lookup — the exact mirror of AccountNotFound)")
    void entry_not_found_maps_to_404() {
        ProblemDetail problem = handler.entryNotFound(
                new EntryNotFound(new EntryId(UUID.randomUUID())));
        assertThat(problem.getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("InvalidEntryInput → 400 (domain defense-in-depth below web validation)")
    void invalid_entry_input_maps_to_400() {
        ProblemDetail problem = handler.invalidEntryInput(
                new InvalidEntryInput("entry description must not be blank when present"));
        assertThat(problem.getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("MissingTokenSubject → 401 with the RFC 6750 challenge — defective credential, not defective request")
    void missing_token_subject_maps_to_401() {
        var response = handler.missingTokenSubject(new MissingTokenSubject());
        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getHeaders().getFirst("WWW-Authenticate"))
                .as("RFC 6750: every 401 names the challenge scheme")
                .startsWith("Bearer");
        // Bare problem, no pinned ProblemTypes URI — the auth posture (type renders as
        // about:blank), exactly like the 404 mappings above.
        ProblemDetail problem = response.getBody();
        assertThat(problem).isNotNull();
        assertThat(problem.getStatus()).isEqualTo(401);
        assertThat(problem.getDetail()).contains("subject");
    }
}
