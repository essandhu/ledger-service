package io.github.essandhu.ledger.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import io.github.essandhu.ledger.application.port.in.StatementCursor;
import io.github.essandhu.ledger.application.port.in.StatementFilter;
import io.github.essandhu.ledger.domain.model.AccountId;
import io.github.essandhu.ledger.domain.model.EntryId;
import io.github.essandhu.ledger.domain.model.JournalEntry;
import io.github.essandhu.ledger.domain.model.Posting;

/**
 * Driven port for journal persistence; implemented by the JPA adapter. Deliberately has no
 * update or delete operation — insert-only is layer 1 of I3 at the port boundary (layer 2 is
 * the {@code @Immutable} mapping, layer 3 the absent UPDATE/DELETE grants on
 * {@code journal_entry} and {@code posting}). Since M3 it also owns the READS over postings —
 * the as-of derivation and the statement page — because ADR-0002 pins those as queries against
 * the append-only source of truth, never the snapshot ({@link BalanceRepository} stays the
 * snapshot's port).
 */
public interface JournalRepository {

    /** Aggregate over one account's postings up to a cut: Σ amount and the row count. */
    record PostingAggregate(long sum, long count) {
    }

    /**
     * Persists a new entry header together with all its postings. Inserting an existing id is
     * a programming error.
     */
    void insert(JournalEntry entry);

    Optional<JournalEntry> findById(EntryId id);

    /**
     * The entry posted under (createdBy, idempotencyKey), if any — the M4 lookup over V3's
     * permanent partial backstop index. This is what makes ADR-0004 option 3b's degradation
     * REAL rather than aspirational: after an idempotency record is purged, the entry itself
     * still answers "this key already succeeded", so a late retry replays (with a
     * reconstructed body) instead of double-posting or erroring. At most one row can match,
     * by the backstop index's uniqueness.
     */
    Optional<JournalEntry> findByCreatorAndKey(String createdBy, String idempotencyKey);

    /**
     * Whether a REVERSAL pointing at {@code originalId} already exists (I11: at most once).
     * The reversal use case evaluates this INSIDE the locked section — the shared account-set
     * lock serializes double-reversal races (ADR-0003), and the partial unique index
     * {@code journal_entry_reversed_once} is the at-rest backstop.
     */
    boolean reversalExistsFor(EntryId originalId);

    /**
     * ADR-0002's as-of derivation, verbatim: Σ amount and COUNT(*) over the account's
     * postings with {@code posted_at <= at} — inclusive, I10's boundary. Never reads the
     * snapshot. The sum always fits 64-bit minor units: same-entry legs share their entry's
     * posted_at, so every instant cut is an entry boundary, and every entry-boundary prefix
     * equals a balance that once committed under M2's checked arithmetic. (POSTING-level
     * prefixes carry no such guarantee — a same-account {+X, −X} entry passes through an
     * unrepresentable midpoint — and are never evaluated: SQL's SUM aggregates wider than
     * bigint internally, so only the final value must fit.)
     */
    PostingAggregate sumPostingsAsOf(AccountId accountId, Instant at);

    /**
     * One keyset page of an account's statement (PLAN §5): postings inside the
     * {@code (from, to]} window of {@code filter}, strictly after {@code after} when present,
     * in {@code (posted_at, id)} ascending order — id ties broken BYTEWISE, PostgreSQL's uuid
     * order — at most {@code limit} rows, served by the {@code posting_account_statement}
     * index. Stable under concurrent appends: a new posting only ever sorts after every
     * position already handed out (the posted_at clamp, PLAN §4.6).
     */
    List<Posting> statementLines(AccountId accountId, StatementFilter filter,
            Optional<StatementCursor> after, int limit);
}
