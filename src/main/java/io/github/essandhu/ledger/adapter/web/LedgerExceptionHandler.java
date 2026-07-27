package io.github.essandhu.ledger.adapter.web;

import java.util.Map;
import java.util.TreeMap;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import io.github.essandhu.ledger.application.port.in.AccountNotFound;
import io.github.essandhu.ledger.application.port.in.EntryNotFound;
import io.github.essandhu.ledger.application.port.in.IdempotencyKeyConflict;
import io.github.essandhu.ledger.application.port.in.InvalidIdempotencyKey;
import io.github.essandhu.ledger.application.port.in.ReconciliationRunNotFound;
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

/**
 * RFC 9457 mappings (PLAN §5). The status split: 400 = the request itself can never be valid
 * (malformed body, bad enum, invalid currency); 401 = the credential is defective (a bearer
 * token without a subject — {@link MissingTokenSubject}); 422 = well-formed but rejected by a
 * business rule, each rule with its pinned {@link ProblemTypes} URI; 404 = path-addressed
 * resource absent; 409 = concurrent modification. Extending
 * {@link ResponseEntityExceptionHandler} gives every standard MVC exception (binding, type
 * mismatch, method validation) a problem body too.
 *
 * <p>M2, discharging M1's forward note here: {@link AccountNotFound} and {@link EntryNotFound}
 * → 404 stay scoped to PATH lookups; an unknown account referenced inside a posting payload is
 * {@link UnknownPostingAccount} → 422 {@code unknown-account} — the different error with
 * different semantics the M1 javadocs promised, never folded into the 404. The posting 422s
 * carry machine-readable properties where the domain error carries structure (per-currency
 * residuals, offending account ids) — the {@code errors}-property precedent below.
 */
@RestControllerAdvice
class LedgerExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(InvalidStatusTransition.class)
    ProblemDetail invalidStatusTransition(InvalidStatusTransition exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_CONTENT, exception.getMessage());
        problem.setType(ProblemTypes.INVALID_STATUS_TRANSITION);
        problem.setTitle("Invalid status transition");
        return problem;
    }

    @ExceptionHandler(AccountClosed.class)
    ProblemDetail accountClosed(AccountClosed exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_CONTENT, exception.getMessage());
        problem.setType(ProblemTypes.ACCOUNT_CLOSED);
        problem.setTitle("Account is closed");
        return problem;
    }

    @ExceptionHandler(FieldNotWritable.class)
    ProblemDetail fieldNotWritable(FieldNotWritable exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_CONTENT, exception.getMessage());
        problem.setType(ProblemTypes.FIELD_NOT_WRITABLE);
        problem.setTitle("Field is not writable");
        return problem;
    }

    @ExceptionHandler(AccountNotFound.class)
    ProblemDetail accountNotFound(AccountNotFound exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler(InvalidAccountInput.class)
    ProblemDetail invalidAccountInput(InvalidAccountInput exception) {
        // Normally shadowed by bean validation at the DTO boundary; reachable for rules the
        // DTO cannot express (e.g. blank rename) — same 400 either way, one status story.
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ProblemDetail optimisticLockConflict(OptimisticLockingFailureException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "the account was modified concurrently; re-read and retry");
        problem.setType(ProblemTypes.CONCURRENT_MODIFICATION);
        problem.setTitle("Concurrent modification");
        return problem;
    }

    // ── M2 posting rejections (PLAN §5) — slug = metric reason tag (PLAN §8) ───────────────

    @ExceptionHandler(UnbalancedEntry.class)
    ProblemDetail unbalancedEntry(UnbalancedEntry exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_CONTENT, exception.getMessage());
        problem.setType(ProblemTypes.UNBALANCED_ENTRY);
        problem.setTitle("Entry does not balance");
        // The exact integer residual per imbalanced currency (I1 knows no epsilon) — TreeMap
        // so the serialized property is deterministically ordered, like the errors list below.
        Map<String, Long> residuals = new TreeMap<>();
        exception.residuals().forEach((currency, residual) -> residuals.put(currency.value(), residual));
        problem.setProperty("residuals", residuals);
        return problem;
    }

    @ExceptionHandler(TooFewPostings.class)
    ProblemDetail tooFewPostings(TooFewPostings exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_CONTENT, exception.getMessage());
        problem.setType(ProblemTypes.TOO_FEW_POSTINGS);
        problem.setTitle("Too few postings");
        return problem;
    }

    @ExceptionHandler(ZeroAmountPosting.class)
    ProblemDetail zeroAmountPosting(ZeroAmountPosting exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_CONTENT, exception.getMessage());
        problem.setType(ProblemTypes.ZERO_AMOUNT_POSTING);
        problem.setTitle("Zero-amount posting");
        problem.setProperty("accountId", exception.accountId().value());
        return problem;
    }

    @ExceptionHandler(CurrencyMismatch.class)
    ProblemDetail currencyMismatch(CurrencyMismatch exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_CONTENT, exception.getMessage());
        problem.setType(ProblemTypes.CURRENCY_MISMATCH);
        problem.setTitle("Currency mismatch");
        return problem;
    }

    @ExceptionHandler(AmountOverflow.class)
    ProblemDetail amountOverflow(AmountOverflow exception) {
        // A 422, deliberately not a 500: checked arithmetic turning 64-bit overflow into a
        // client-facing rejection is the whole point of ADR-0001's addExact discipline.
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_CONTENT, exception.getMessage());
        problem.setType(ProblemTypes.AMOUNT_OVERFLOW);
        problem.setTitle("Amount overflow");
        return problem;
    }

    @ExceptionHandler(OverdraftViolation.class)
    ProblemDetail overdraftViolation(OverdraftViolation exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_CONTENT, exception.getMessage());
        problem.setType(ProblemTypes.OVERDRAFT);
        problem.setTitle("Overdraft rejected");
        problem.setProperty("accountId", exception.accountId().value());
        return problem;
    }

    @ExceptionHandler(AccountFrozen.class)
    ProblemDetail accountFrozen(AccountFrozen exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_CONTENT, exception.getMessage());
        problem.setType(ProblemTypes.ACCOUNT_FROZEN);
        problem.setTitle("Account is frozen");
        return problem;
    }

    @ExceptionHandler(UnknownPostingAccount.class)
    ProblemDetail unknownPostingAccount(UnknownPostingAccount exception) {
        // The payload-side sibling of the 404s: the resource (the entry collection) exists,
        // the request semantics are refused — 422, per the M1 forward note in the class javadoc.
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_CONTENT, exception.getMessage());
        problem.setType(ProblemTypes.UNKNOWN_ACCOUNT);
        problem.setTitle("Unknown account in postings");
        problem.setProperty("accountIds", exception.accountIds().stream()
                .map(accountId -> accountId.value().toString())
                .sorted()
                .toList());
        return problem;
    }

    @ExceptionHandler(EntryAlreadyReversed.class)
    ProblemDetail entryAlreadyReversed(EntryAlreadyReversed exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_CONTENT, exception.getMessage());
        problem.setType(ProblemTypes.ENTRY_ALREADY_REVERSED);
        problem.setTitle("Entry is already reversed");
        return problem;
    }

    @ExceptionHandler(AccountBalanceNotZero.class)
    ProblemDetail accountBalanceNotZero(AccountBalanceNotZero exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_CONTENT, exception.getMessage());
        problem.setType(ProblemTypes.ACCOUNT_BALANCE_NOT_ZERO);
        problem.setTitle("Account balance is not zero");
        problem.setProperty("accountId", exception.accountId().value());
        return problem;
    }

    @ExceptionHandler(IdempotencyKeyConflict.class)
    ProblemDetail idempotencyKeyConflict(IdempotencyKeyConflict exception) {
        // M4 (ADR-0004, I9): key reuse with a different payload — a business-rule 422 with its
        // pinned slug, per the IETF idempotency draft's recommendation. The key rides along as
        // a machine-readable property (it is the client's own value — nothing disclosed).
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_CONTENT, exception.getMessage());
        problem.setType(ProblemTypes.IDEMPOTENCY_KEY_CONFLICT);
        problem.setTitle("Idempotency key conflict");
        problem.setProperty("idempotencyKey", exception.idempotencyKey());
        return problem;
    }

    @ExceptionHandler(InvalidIdempotencyKey.class)
    ProblemDetail invalidIdempotencyKey(InvalidIdempotencyKey exception) {
        // M4 key shape rules (blank, oversized, control characters): the request can never be
        // valid — bare 400 like every shape violation; the typed slug belongs to the conflict.
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(EntryNotFound.class)
    ProblemDetail entryNotFound(EntryNotFound exception) {
        // Path-addressed miss — bare 404, about:blank, the exact mirror of accountNotFound.
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler(ReconciliationRunNotFound.class)
    ProblemDetail reconciliationRunNotFound(ReconciliationRunNotFound exception) {
        // M6: same path-addressed-miss doctrine as the account and entry 404s.
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler(InvalidQueryInstant.class)
    ProblemDetail invalidQueryInstant(InvalidQueryInstant exception) {
        // M3 read params: parseable in java.time, unbindable as timestamptz — the 400 family
        // (never a 500 from the bind), same bare-problem posture as every shape violation.
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(InvalidCursor.class)
    ProblemDetail invalidCursor(InvalidCursor exception) {
        // M3 statement cursor: not a token we issued (or not for this account) — a shape
        // violation like any malformed query param, so bare 400, no ProblemTypes slug (typed
        // slugs are the business-rule vocabulary; the class javadoc pins the split).
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(InvalidEntryInput.class)
    ProblemDetail invalidEntryInput(InvalidEntryInput exception) {
        // The posting-side sibling of invalidAccountInput: normally shadowed by bean validation
        // at the DTO boundary; reachable for rules the DTO cannot express (blank description).
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(MissingTokenSubject.class)
    ResponseEntity<ProblemDetail> missingTokenSubject(MissingTokenSubject exception) {
        // 401, bare about:blank — the auth family's posture (ProblemAuthResponses), not the
        // business-rule 422 family: the credential is defective, not the request. RFC 6750
        // obliges every 401 to carry the challenge scheme, so the header rides along — which is
        // why this one mapping returns a ResponseEntity instead of a bare ProblemDetail.
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED, exception.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .body(problem);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        exception.getBody().setProperty("errors", exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .sorted()
                .toList());
        return super.handleMethodArgumentNotValid(exception, headers, status, request);
    }
}
