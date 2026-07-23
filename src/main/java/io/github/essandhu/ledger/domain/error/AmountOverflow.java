package io.github.essandhu.ledger.domain.error;

/**
 * Checked 64-bit arithmetic refused to wrap (ADR-0001): a running sum or balance delta left
 * the representable range of {@code long} minor units. Surfaces as a 422 rejection — the client
 * sending amounts near {@code Long.MAX_VALUE} is told so, instead of a 500 or, far worse, a
 * silently wrapped total that happens to look balanced. Raised where the application catches
 * {@link ArithmeticException} from checked arithmetic ({@code Math.addExact},
 * {@code Math.negateExact}, the {@code multiplyExact} in {@code AccountBalance.natural}) at an
 * accumulation point; a bare {@code ArithmeticException} escaping {@code Money} or
 * {@code AccountBalance} itself means the caller skipped that translation, which is a bug, not
 * a rejection.
 */
public class AmountOverflow extends RuntimeException {

    public AmountOverflow(String message) {
        super(message);
    }
}
