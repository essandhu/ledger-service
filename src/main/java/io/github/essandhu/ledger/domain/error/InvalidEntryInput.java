package io.github.essandhu.ledger.domain.error;

/**
 * A supplied entry value that can never be valid regardless of ledger state (blank description,
 * control characters, ...). Maps to HTTP 400: request well-formedness, not a business-state
 * rule — the sibling of {@link InvalidAccountInput} for the posting side, as opposed to the
 * 422 rejections ({@link UnbalancedEntry}, {@link OverdraftViolation}, ...) where the request
 * is well-formed and the ledger refuses the semantics. The web adapter's bean validation
 * intentionally shadows most of these; the domain guard is defense in depth for non-HTTP
 * callers.
 */
public class InvalidEntryInput extends RuntimeException {

    public InvalidEntryInput(String message) {
        super(message);
    }
}
