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

    // M2, the posting rejection vocabulary (PLAN §5): these slugs ARE the reason tags of the
    // ledger.posting.rejected counter (PLAN §8) — one vocabulary, so a dashboard series and a
    // client's problem document dereference the same name. ACCOUNT_CLOSED above joined the
    // family unchanged: postings to CLOSED accounts reuse the M1 slug, exactly as its javadoc
    // promised. ACCOUNT_BALANCE_NOT_ZERO belongs to the close use case, not the posting ports,
    // and is therefore deliberately absent from the metric's reason set.
    public static final URI UNBALANCED_ENTRY = URI.create(BASE + "unbalanced-entry");
    public static final URI TOO_FEW_POSTINGS = URI.create(BASE + "too-few-postings");
    public static final URI ZERO_AMOUNT_POSTING = URI.create(BASE + "zero-amount-posting");
    public static final URI CURRENCY_MISMATCH = URI.create(BASE + "currency-mismatch");
    public static final URI AMOUNT_OVERFLOW = URI.create(BASE + "amount-overflow");
    public static final URI OVERDRAFT = URI.create(BASE + "overdraft");
    public static final URI ACCOUNT_FROZEN = URI.create(BASE + "account-frozen");
    public static final URI UNKNOWN_ACCOUNT = URI.create(BASE + "unknown-account");
    public static final URI ENTRY_ALREADY_REVERSED = URI.create(BASE + "entry-already-reversed");
    public static final URI ACCOUNT_BALANCE_NOT_ZERO = URI.create(BASE + "account-balance-not-zero");

    private ProblemTypes() {
    }
}
