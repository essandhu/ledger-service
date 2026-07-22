package io.github.essandhu.ledger.domain.error;

/**
 * A supplied value that can never be valid regardless of account state (blank name, non-ISO
 * currency, ...). Maps to HTTP 400: request well-formedness, not a business-state rule — as
 * opposed to state-dependent rejections ({@link InvalidStatusTransition}, {@link AccountClosed}),
 * which map to 422. The web adapter's bean validation intentionally shadows most of these; the
 * domain guard is defense in depth for non-HTTP callers.
 */
public class InvalidAccountInput extends RuntimeException {

    public InvalidAccountInput(String message) {
        super(message);
    }
}
