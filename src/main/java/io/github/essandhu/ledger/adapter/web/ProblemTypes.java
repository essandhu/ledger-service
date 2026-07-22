package io.github.essandhu.ledger.adapter.web;

import java.net.URI;

/**
 * The machine-readable problem `type` vocabulary (PLAN §5: one stable URI per rejection rule).
 * Absolute on purpose — RFC 9457 resolves relative type references against the request URI,
 * which would give the same problem different identifiers per endpoint. These strings are
 * public API: pinned by tests, never changed once published. Dereferenceable documentation
 * pages arrive with M7; RFC 9457 requires only a stable identifier. Slugs are chosen to align
 * with the M2 {@code ledger.posting.rejected} metric reason tags (PLAN §8) so problems and
 * metrics share one vocabulary.
 */
public final class ProblemTypes {

    public static final String BASE = "https://essandhu.github.io/ledger/problems/";

    public static final URI INVALID_STATUS_TRANSITION = URI.create(BASE + "invalid-status-transition");
    public static final URI ACCOUNT_CLOSED = URI.create(BASE + "account-closed");
    public static final URI FIELD_NOT_WRITABLE = URI.create(BASE + "field-not-writable");
    public static final URI CONCURRENT_MODIFICATION = URI.create(BASE + "concurrent-modification");

    private ProblemTypes() {
    }
}
