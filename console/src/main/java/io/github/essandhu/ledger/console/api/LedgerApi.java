package io.github.essandhu.ledger.console.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The ledger API's read contract, mirrored field-for-field — these records ARE the wire
 * shapes (the core's {@code AccountResponse}, {@code BalanceResponse},
 * {@code StatementPageResponse}, {@code EntryResponse} et al.), so any drift fails loudly at
 * deserialization instead of rendering wrong.
 *
 * <p>Two deliberate absences mirror the API: there is no {@code direction} field anywhere —
 * the SIGN of a raw amount is the direction (positive = debit) — and no {@code nature} field
 * on accounts, because the console never computes natural figures itself: the balance
 * surface serves BOTH {@code balance} (natural = raw × direction(type), computed by the
 * core) and {@code rawBalance}, and everything else renders raw with the side derived from
 * the sign.
 */
public final class LedgerApi {

    private LedgerApi() {
    }

    /** Raw minor units + ISO-4217 code; the exponent (JPY 0, EUR 2, BHD 3) is console-owned. */
    public record Money(long amount, String currency) {
    }

    public enum AccountType {
        ASSET, LIABILITY, EQUITY, INCOME, EXPENSE
    }

    public enum AccountStatus {
        ACTIVE, FROZEN, CLOSED
    }

    public enum EntryType {
        TRANSFER, JOURNAL, REVERSAL
    }

    public record Account(UUID id, String name, String currency, AccountType type,
            AccountStatus status, boolean allowNegative, Instant createdAt, Instant updatedAt) {
    }

    public record AccountPage(List<Account> content, int page, int size, long totalElements) {

        /** The API sends no totalPages — computed here, the one derived paging fact. */
        public long totalPages() {
            return size == 0 ? 0 : Math.ceilDiv(totalElements, size);
        }
    }

    /**
     * {@code balance} is the NATURAL figure (the number to show); {@code rawBalance} is the
     * debit-positive raw sum that reconciles against statement lines. {@code asOf} is ABSENT
     * (null here) on live-snapshot reads and present only on as-of queries.
     */
    public record Balance(UUID accountId, AccountType type, Money balance, Money rawBalance,
            long postingCount, Instant asOf) {
    }

    /** Statement line; {@code amount} is RAW signed (positive = debit leg). */
    public record StatementLine(UUID id, UUID entryId, Money amount, Instant postedAt) {
    }

    /**
     * {@code nextCursor} is a resume position, NOT a has-more signal: it echoes the request's
     * own cursor on an empty follow-up page and is null only when a cursor-less request found
     * nothing. "More to load" must key off {@code content.size() == limit}.
     */
    public record StatementPage(List<StatementLine> content, String nextCursor) {
    }

    public record Posting(UUID id, UUID accountId, Money amount) {
    }

    /** {@code reversalOf} is non-null IFF {@code entryType == REVERSAL} (core invariant). */
    public record Entry(UUID id, EntryType entryType, String description, UUID reversalOf,
            String createdBy, Instant postedAt, List<Posting> postings) {
    }

    public enum RunStatus {
        RUNNING, CLEAN, DRIFT, FAILED
    }

    /**
     * One reconciliation sweep (I15). {@code finishedAt} and every result count are ABSENT —
     * hence boxed and nullable here — until the run has a verdict: a RUNNING row observed
     * mid-sweep and a FAILED row simply have less to say, and zeroes would read as "checked
     * nothing, found nothing" instead of "did not finish" (the core's own NON_NULL posture).
     */
    public record Run(UUID id, RunStatus status, Instant startedAt, Instant finishedAt,
            String triggeredBy, Long accountsChecked, Long driftCount,
            Long currencyMismatchCount, Long postedAtMismatchCount,
            Long unbalancedCurrencyCount) {
    }

    /** The run history, NEWEST first as the API orders it (descending id ⇒ reverse chronological). */
    public record RunPage(List<Run> content, int page, int size, long totalElements) {

        /** The API sends no totalPages — computed here, as on {@link AccountPage}. */
        public long totalPages() {
            return size == 0 ? 0 : Math.ceilDiv(totalElements, size);
        }
    }

    /**
     * One drifted account in one sweep: the snapshot pair the run saw, the pair it recomputed
     * from postings, and {@code delta = snapshotBalance − computedBalance}. Deliberately NO
     * currency field — the API's finding carries none, so the console renders bare minor units
     * and says so, rather than inventing an exponent for a number it cannot attribute.
     */
    public record Finding(UUID id, UUID accountId, long snapshotBalance, long snapshotCount,
            long computedBalance, long computedCount, long delta) {
    }

    /** One page of a run's findings, in detection order (id-ordered, UUIDv7). */
    public record FindingsPage(List<Finding> content, int page, int size, long totalElements) {

        public long totalPages() {
            return size == 0 ? 0 : Math.ceilDiv(totalElements, size);
        }
    }
}
