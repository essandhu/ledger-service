package io.github.essandhu.ledger.support.fakes;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.essandhu.ledger.application.port.in.StatementCursor;
import io.github.essandhu.ledger.application.port.in.StatementFilter;
import io.github.essandhu.ledger.application.port.out.BalanceRepository;
import io.github.essandhu.ledger.application.port.out.JournalRepository;
import io.github.essandhu.ledger.domain.model.AccountId;
import io.github.essandhu.ledger.domain.model.EntryId;
import io.github.essandhu.ledger.domain.model.EntryType;
import io.github.essandhu.ledger.domain.model.JournalEntry;
import io.github.essandhu.ledger.domain.model.Posting;

/**
 * Hand-written fake (TEST-STRATEGY §2.1: fakes over mock-framework stubs, so the port contract
 * is enforced, not just echoed): a real in-memory journal with the same observable semantics as
 * the JPA adapter — insert-only (I3 has no update path to fake), duplicate-id failure, and
 * reversal existence derived by scanning for a REVERSAL pointing at the original, exactly as
 * the adapter's query does. Counts inserts so "a rejected posting writes NOTHING" (ADR-0004)
 * is assertable. The M3 reads reproduce the SQL semantics precisely: id ties break BYTEWISE
 * (PostgreSQL's uuid order — {@code UUID.compareTo} would sort any id with its first bit set
 * to the wrong end), and the as-of sum aggregates wider than 64 bits with only the final
 * value checked, exactly like {@code CAST(SUM(amount) AS bigint)}.
 */
public final class FakeJournalRepository implements JournalRepository {

    /** The statement keyset (PLAN §5): posted_at, then id in database order — the id
     * tiebreak is THE shared bytewise definition, not a local re-derivation. */
    private static final Comparator<Posting> STATEMENT_ORDER = Comparator
            .comparing(Posting::postedAt)
            .thenComparing(posting -> posting.id().value(),
                    BalanceRepository.UUID_BYTEWISE_ORDER);

    private final Map<EntryId, JournalEntry> rows = new LinkedHashMap<>();
    private int insertCalls;

    @Override
    public void insert(JournalEntry entry) {
        if (entry.idempotencyKey() != null) {
            // The V3 backstop index (ADR-0004): at most one entry per (created_by, key), ever.
            // The fake polices it like the database would, so a service that stopped checking
            // the record store cannot pass its unit tests by accident.
            boolean keyTaken = rows.values().stream()
                    .anyMatch(existing -> entry.createdBy().equals(existing.createdBy())
                            && entry.idempotencyKey().equals(existing.idempotencyKey()));
            if (keyTaken) {
                throw new IllegalStateException("duplicate (created_by, idempotency_key): ("
                        + entry.createdBy() + ", " + entry.idempotencyKey() + ")");
            }
        }
        if (rows.putIfAbsent(entry.id(), entry) != null) {
            throw new IllegalStateException("duplicate insert for entry " + entry.id());
        }
        insertCalls++;
    }

    @Override
    public Optional<JournalEntry> findById(EntryId id) {
        return Optional.ofNullable(rows.get(id));
    }

    @Override
    public Optional<JournalEntry> findByCreatorAndKey(String createdBy, String idempotencyKey) {
        // At most one row can match — insert() polices the backstop uniqueness above.
        return rows.values().stream()
                .filter(entry -> createdBy.equals(entry.createdBy())
                        && idempotencyKey.equals(entry.idempotencyKey()))
                .findFirst();
    }

    @Override
    public boolean reversalExistsFor(EntryId originalId) {
        return rows.values().stream()
                .anyMatch(entry -> entry.entryType() == EntryType.REVERSAL
                        && originalId.equals(entry.reversalOf()));
    }

    @Override
    public PostingAggregate sumPostingsAsOf(AccountId accountId, Instant at) {
        BigInteger sum = BigInteger.ZERO;
        long count = 0;
        for (Posting posting : postingsOf(accountId)) {
            if (!posting.postedAt().isAfter(at)) { // posted_at <= at, I10's inclusive cut
                sum = sum.add(BigInteger.valueOf(posting.amount().amount()));
                count++;
            }
        }
        // longValueExact mirrors the SQL CAST: intermediate widths never matter, the final
        // value must fit — and cannot fail on real data (JournalRepository javadoc).
        return new PostingAggregate(sum.longValueExact(), count);
    }

    @Override
    public List<Posting> statementLines(AccountId accountId, StatementFilter filter,
            Optional<StatementCursor> after, int limit) {
        return postingsOf(accountId).stream()
                .filter(posting -> filter.fromExclusive()
                        .map(from -> posting.postedAt().isAfter(from)).orElse(true))
                .filter(posting -> filter.toInclusive()
                        .map(to -> !posting.postedAt().isAfter(to)).orElse(true))
                .filter(posting -> after.map(cursor -> strictlyAfter(posting, cursor))
                        .orElse(true))
                .limit(limit)
                .toList();
    }

    private List<Posting> postingsOf(AccountId accountId) {
        return rows.values().stream()
                .flatMap(entry -> entry.postings().stream())
                .filter(posting -> posting.accountId().equals(accountId))
                .sorted(STATEMENT_ORDER)
                .toList();
    }

    private static boolean strictlyAfter(Posting posting, StatementCursor cursor) {
        int byTime = posting.postedAt().compareTo(cursor.postedAt());
        if (byTime != 0) {
            return byTime > 0;
        }
        return BalanceRepository.UUID_BYTEWISE_ORDER
                .compare(posting.id().value(), cursor.id().value()) > 0;
    }

    /** Test-only seeding that bypasses the use-case layer (and the insert counter). */
    public void seed(JournalEntry entry) {
        rows.put(entry.id(), entry);
    }

    public int insertCalls() {
        return insertCalls;
    }
}
