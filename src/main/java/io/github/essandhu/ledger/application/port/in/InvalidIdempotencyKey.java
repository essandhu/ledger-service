package io.github.essandhu.ledger.application.port.in;

import java.util.Objects;

/**
 * Shape rules for the client-supplied {@code Idempotency-Key} (M4): present (the web layer's
 * required-header 400 fires first for absence), non-blank, no control characters, no commas,
 * at most {@value #MAX_LENGTH} characters. A violation is a request that can never be valid —
 * the 400 family, bare {@code about:blank} like every shape violation (typed slugs are
 * reserved for business rules). The comma ban is transport hygiene, not taste: HTTP stacks
 * join duplicate header fields with commas (RFC 9110 list syntax), so a comma-bearing key is
 * indistinguishable from an accidentally doubled header — and a doubled header recorded as
 * "K,K" would MISS the replay when an intermediary later dedupes the retry to "K", the exact
 * double-post the key exists to prevent. Rejecting commas makes the doubled-header defect a
 * loud 400 on first contact instead. Enforced in the command constructors so every path into
 * the use cases hits the same guard; mirrored at rest by the V4 CHECKs on {@code idem_key}
 * and {@code journal_entry.idempotency_key}.
 */
public class InvalidIdempotencyKey extends RuntimeException {

    /** Generous for the recommended UUID keys (36 chars) while bounding index entries; the V4
     * CHECK pins the same number. */
    public static final int MAX_LENGTH = 200;

    public InvalidIdempotencyKey(String message) {
        super(message);
    }

    /**
     * Validates and returns the key. A null key here is a programming error (the web layer
     * rejects an absent header before any command is built), hence NPE, not a client 400.
     */
    public static String requireValid(String idempotencyKey) {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        if (idempotencyKey.isBlank()) {
            throw new InvalidIdempotencyKey("Idempotency-Key must not be blank");
        }
        if (idempotencyKey.length() > MAX_LENGTH) {
            throw new InvalidIdempotencyKey(
                    "Idempotency-Key exceeds %d characters (got %d)"
                            .formatted(MAX_LENGTH, idempotencyKey.length()));
        }
        for (int i = 0; i < idempotencyKey.length(); i++) {
            char c = idempotencyKey.charAt(i);
            if (Character.isISOControl(c)) {
                throw new InvalidIdempotencyKey(
                        "Idempotency-Key must not contain control characters");
            }
            if (c == ',') {
                throw new InvalidIdempotencyKey(
                        "Idempotency-Key must not contain commas (HTTP joins duplicate "
                                + "headers with commas, so a comma-bearing key is ambiguous)");
            }
        }
        return idempotencyKey;
    }
}
