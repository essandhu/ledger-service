package io.github.essandhu.ledger.application.port.in;

/**
 * Offset pagination request. Named to not collide with Spring Data's PageRequest, which the
 * persistence adapter maps this onto. The web layer validates request params first (400); these
 * guards are the port's own contract for non-HTTP callers.
 */
public record PageSpec(int page, int size) {

    public static final int MAX_SIZE = 100;

    public PageSpec {
        if (page < 0) {
            throw new IllegalArgumentException("page must be >= 0");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + MAX_SIZE);
        }
    }
}
