package io.github.essandhu.ledger.adapter.web;

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
import io.github.essandhu.ledger.domain.error.AccountClosed;
import io.github.essandhu.ledger.domain.error.InvalidAccountInput;
import io.github.essandhu.ledger.domain.error.InvalidStatusTransition;

/**
 * RFC 9457 mappings (PLAN §5). The status split: 400 = the request itself can never be valid
 * (malformed body, bad enum, invalid currency); 422 = well-formed but rejected by a business
 * rule, each rule with its pinned {@link ProblemTypes} URI; 404 = path-addressed resource
 * absent; 409 = concurrent modification. Extending {@link ResponseEntityExceptionHandler}
 * gives every standard MVC exception (binding, type mismatch, method validation) a problem
 * body too.
 *
 * <p>M2 note: {@link AccountNotFound} → 404 is scoped to path lookups. An unknown account
 * referenced inside a posting payload is a different error with different semantics
 * (422, PLAN §5) — it must NOT reuse this mapping.
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
