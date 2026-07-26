package io.github.essandhu.ledger.domain.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A posted fact (PLAN §4.1): the immutable header of a balanced set of {@link Posting}s. Never
 * updated, never deleted, no mutators anywhere — layer 1 of I3. Built through {@link #post}
 * from an already-validated {@link EntryDraft}; the canonical constructor stays public for
 * rehydration by adapters and enforces referential shape only (reversal linkage, posting
 * ownership) — the value-level rules I1/I2 were proven at draft time and are re-proven at rest
 * by the V3 CHECKs and reconciliation (I15). A shape violation here is a programming error
 * ({@link IllegalArgumentException}), not a client rejection: no request payload can reach it
 * past the draft.
 *
 * <p>I11 linkage: {@code reversalOf} is set if and only if {@code entryType == REVERSAL} —
 * mirrored by the {@code journal_entry_reversal_shape} CHECK. "Reversed" is a derived property
 * of the ORIGINAL entry (a REVERSAL points at it; the original row never changes), which is why
 * this type has no {@code reversed} flag to mutate.
 *
 * <p>{@code createdBy} is the JWT subject of the caller (PLAN §7) and, from M4 on, one half of
 * the idempotency backstop index (ADR-0004); {@code idempotencyKey} is the other half — the
 * client-supplied key this entry was posted under, kept on the entry forever as the permanent
 * double-post guard and audit answer to "which request created this". Nullable: pre-M4 rows
 * (and any future keyless write path) carry none, and V3's backstop index is partial for
 * exactly that reason. Shape rules for a present key are enforced upstream (the command's
 * {@code InvalidIdempotencyKey} guard) and at rest (V4 CHECKs) — the domain accepts what those
 * layers admitted. {@code postedAt} is the single instant the service read from its Clock while
 * holding the account locks (PLAN §4.6), shared by header and every leg.
 */
public record JournalEntry(
        EntryId id,
        EntryType entryType,
        String description,
        EntryId reversalOf,
        String createdBy,
        String idempotencyKey,
        Instant postedAt,
        List<Posting> postings) {

    public JournalEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(entryType, "entryType");
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(postedAt, "postedAt");
        Objects.requireNonNull(postings, "postings");
        postings = List.copyOf(postings); // defensive, immutable; rejects null elements
        if ((entryType == EntryType.REVERSAL) != (reversalOf != null)) {
            throw new IllegalArgumentException(
                    "reversal_of must be set iff entry_type is REVERSAL, got %s with reversalOf=%s (I11)"
                            .formatted(entryType, reversalOf == null ? null : reversalOf.value()));
        }
        for (Posting posting : postings) {
            if (!posting.entryId().equals(id)) {
                throw new IllegalArgumentException(
                        "posting %s belongs to entry %s, not %s".formatted(
                                posting.id().value(), posting.entryId().value(), id.value()));
            }
        }
    }

    /**
     * Turns a validated draft into the posted fact: pairs legs with posting ids BY POSITION
     * (leg order is preserved end to end — I11's exactness proof compares positionally) and
     * stamps every posting with this entry's id and the one {@code postedAt} the service read
     * under the lock (PLAN §4.6). A posting-id count that disagrees with the leg count is a
     * programming error in the caller's id generation, not a client rejection.
     */
    public static JournalEntry post(EntryId id, EntryType entryType, EntryDraft draft,
            EntryId reversalOf, String createdBy, String idempotencyKey, Instant postedAt,
            List<PostingId> postingIds) {
        Objects.requireNonNull(draft, "draft");
        Objects.requireNonNull(postingIds, "postingIds");
        List<PostingId> ids = List.copyOf(postingIds);
        List<EntryDraft.Leg> legs = draft.legs();
        if (ids.size() != legs.size()) {
            throw new IllegalArgumentException(
                    "expected %d posting ids for %d legs, got %d".formatted(
                            legs.size(), legs.size(), ids.size()));
        }
        List<Posting> postings = new ArrayList<>(legs.size());
        for (int i = 0; i < legs.size(); i++) {
            EntryDraft.Leg leg = legs.get(i);
            postings.add(new Posting(ids.get(i), id, leg.accountId(), leg.amount(), postedAt));
        }
        return new JournalEntry(id, entryType, draft.description(), reversalOf, createdBy,
                idempotencyKey, postedAt, postings);
    }

    /**
     * I11, the negation half: the reversal INTENT for {@code original} — every leg negated
     * exactly ({@code negateExact} under {@link Money#negate}), account, currency, and order
     * preserved. Returns a draft, not an entry: the reversal walks the same validation, locking,
     * and status checks as any other posting (ADR-0003 — which is why reversing into a FROZEN
     * account fails, a documented operational caveat of PLAN §4.5). The description is the
     * reversing entry's own, nullable like any other. A {@code Long.MIN_VALUE} leg has no
     * 64-bit negation: the {@link ArithmeticException} propagates for the use case to translate
     * (ADR-0001: overflow never wraps).
     */
    public static EntryDraft reversalDraftOf(JournalEntry original, String description) {
        Objects.requireNonNull(original, "original");
        List<EntryDraft.Leg> negated = original.postings().stream()
                .map(posting -> new EntryDraft.Leg(posting.accountId(), posting.amount().negate()))
                .toList();
        return new EntryDraft(description, negated);
    }
}
