package io.github.essandhu.ledger.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.github.essandhu.ledger.domain.error.ZeroAmountPosting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * I11, unit half: a reversal draft negates the original's postings EXACTLY (per-leg, exact
 * integer negation, order preserved), so original + reversal always sum to zero per currency.
 * The at-most-once half (partial unique index, lock-serialized races) is the integration
 * suite's job. Also pins the {@code post} factory shape: id pairing by position, the
 * reversal_of ⇔ REVERSAL linkage rule, and posting ownership.
 */
@DisplayName("I11 (unit half): posting factory shape and exact reversal negation")
class JournalEntryTest {

    private static final Instant T0 = Instant.parse("2026-07-22T10:00:00Z");

    private static final CurrencyCode EUR = new CurrencyCode("EUR");
    private static final CurrencyCode JPY = new CurrencyCode("JPY");

    private static final AccountId CASH =
            new AccountId(UUID.fromString("019817b4-0000-7000-8000-00000000000a"));
    private static final AccountId REVENUE =
            new AccountId(UUID.fromString("019817b4-0000-7000-8000-00000000000b"));
    private static final AccountId VAT =
            new AccountId(UUID.fromString("019817b4-0000-7000-8000-00000000000c"));

    private static final EntryId ENTRY =
            new EntryId(UUID.fromString("019817b4-0000-7000-8000-0000000000e1"));
    private static final EntryId OTHER_ENTRY =
            new EntryId(UUID.fromString("019817b4-0000-7000-8000-0000000000e2"));
    private static final PostingId P1 =
            new PostingId(UUID.fromString("019817b4-0000-7000-8000-0000000000f1"));
    private static final PostingId P2 =
            new PostingId(UUID.fromString("019817b4-0000-7000-8000-0000000000f2"));
    private static final PostingId P3 =
            new PostingId(UUID.fromString("019817b4-0000-7000-8000-0000000000f3"));

    private static EntryDraft.Leg leg(AccountId accountId, long amount, CurrencyCode currency) {
        return new EntryDraft.Leg(accountId, Money.of(amount, currency));
    }

    private static JournalEntry transfer() {
        EntryDraft draft = new EntryDraft("rent, july",
                List.of(leg(CASH, 1099, EUR), leg(REVENUE, -1099, EUR)));
        return JournalEntry.post(ENTRY, EntryType.TRANSFER, draft, null, "alice", "rent-key",
                T0, List.of(P1, P2));
    }

    @Nested
    @DisplayName("post: from validated draft to posted fact")
    class PostFactory {

        @Test
        @DisplayName("pairs legs with posting ids by position, stamping entry id and posted_at")
        void pairs_legs_with_posting_ids_in_order() {
            JournalEntry entry = transfer();
            assertThat(entry.id()).isEqualTo(ENTRY);
            assertThat(entry.entryType()).isEqualTo(EntryType.TRANSFER);
            assertThat(entry.description()).isEqualTo("rent, july");
            assertThat(entry.reversalOf()).isNull();
            assertThat(entry.createdBy()).isEqualTo("alice");
            assertThat(entry.idempotencyKey())
                    .as("M4: the key is part of the posted fact (ADR-0004)")
                    .isEqualTo("rent-key");
            assertThat(entry.postedAt()).isEqualTo(T0);
            assertThat(entry.postings()).containsExactly(
                    new Posting(P1, ENTRY, CASH, Money.of(1099, EUR), T0),
                    new Posting(P2, ENTRY, REVENUE, Money.of(-1099, EUR), T0));
        }

        @Test
        @DisplayName("rejects a posting-id count that disagrees with the leg count")
        void rejects_mismatched_posting_id_count() {
            EntryDraft draft = new EntryDraft(null,
                    List.of(leg(CASH, 1, EUR), leg(REVENUE, -1, EUR)));
            assertThatThrownBy(() ->
                    JournalEntry.post(ENTRY, EntryType.JOURNAL, draft, null, "alice", null, T0,
                            List.of(P1)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("the postings list is immutable — no mutators anywhere (I3, layer 1)")
        void postings_are_immutable() {
            JournalEntry entry = transfer();
            assertThatThrownBy(() -> entry.postings()
                    .add(new Posting(P3, ENTRY, VAT, Money.of(1, EUR), T0)))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("shape: reversal linkage and posting ownership")
    class Shape {

        @Test
        @DisplayName("a REVERSAL must carry its reversal_of link")
        void reversal_requires_link() {
            EntryDraft draft = new EntryDraft(null,
                    List.of(leg(CASH, 1, EUR), leg(REVENUE, -1, EUR)));
            assertThatThrownBy(() ->
                    JournalEntry.post(ENTRY, EntryType.REVERSAL, draft, null, "alice", null, T0,
                            List.of(P1, P2)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a non-REVERSAL must not carry a reversal_of link")
        void non_reversal_must_not_carry_link() {
            EntryDraft draft = new EntryDraft(null,
                    List.of(leg(CASH, 1, EUR), leg(REVENUE, -1, EUR)));
            assertThatThrownBy(() ->
                    JournalEntry.post(ENTRY, EntryType.TRANSFER, draft, OTHER_ENTRY, "alice", null,
                            T0, List.of(P1, P2)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("rejects a posting that belongs to another entry")
        void rejects_foreign_posting() {
            assertThatThrownBy(() -> new JournalEntry(ENTRY, EntryType.JOURNAL, null, null, "alice",
                    null, T0,
                    List.of(new Posting(P1, ENTRY, CASH, Money.of(1, EUR), T0),
                            new Posting(P2, OTHER_ENTRY, REVENUE, Money.of(-1, EUR), T0))))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a posting cannot carry a zero amount — I2's per-leg rule, mirrored by the DB CHECK")
        void posting_rejects_zero_amount() {
            assertThatThrownBy(() -> new Posting(P1, ENTRY, CASH, Money.zero(EUR), T0))
                    .isInstanceOf(ZeroAmountPosting.class)
                    .hasFieldOrPropertyWithValue("accountId", CASH);
        }
    }

    @Nested
    @DisplayName("I11: reversal drafts negate exactly")
    class ReversalNegation {

        @Test
        @DisplayName("negates every leg exactly, preserving account, currency, and order")
        void negates_every_leg_exactly() {
            EntryDraft reversal = JournalEntry.reversalDraftOf(transfer(), "undo rent");
            assertThat(reversal.description()).isEqualTo("undo rent");
            assertThat(reversal.legs()).containsExactly(
                    leg(CASH, -1099, EUR), leg(REVENUE, 1099, EUR));
        }

        @Test
        @DisplayName("original and reversal legs sum to exactly zero per currency")
        void original_plus_reversal_sums_to_zero() {
            EntryDraft draft = new EntryDraft(null, List.of(
                    leg(CASH, 500, EUR), leg(REVENUE, -500, EUR),
                    leg(CASH, 300, JPY), leg(VAT, -300, JPY)));
            JournalEntry original = JournalEntry.post(ENTRY, EntryType.JOURNAL, draft, null,
                    "alice", null, T0, List.of(P1, P2, P3,
                            new PostingId(UUID.fromString("019817b4-0000-7000-8000-0000000000f4"))));
            EntryDraft reversal = JournalEntry.reversalDraftOf(original, null);

            long eur = 0;
            long jpy = 0;
            for (Posting posting : original.postings()) {
                if (posting.amount().currency().equals(EUR)) {
                    eur = Math.addExact(eur, posting.amount().amount());
                } else {
                    jpy = Math.addExact(jpy, posting.amount().amount());
                }
            }
            for (EntryDraft.Leg reversed : reversal.legs()) {
                if (reversed.amount().currency().equals(EUR)) {
                    eur = Math.addExact(eur, reversed.amount().amount());
                } else {
                    jpy = Math.addExact(jpy, reversed.amount().amount());
                }
            }
            assertThat(eur).isZero();
            assertThat(jpy).isZero();
        }

        @Test
        @DisplayName("a null reversal description is legal — the reversing entry may say nothing")
        void reversal_description_may_be_null() {
            EntryDraft reversal = JournalEntry.reversalDraftOf(transfer(), null);
            assertThat(reversal.description()).isNull();
        }

        @Test
        @DisplayName("reversing a Long.MIN_VALUE leg surfaces checked overflow — never a wrapped amount")
        void reversing_min_value_leg_surfaces_checked_overflow() {
            // A valid entry CAN carry a MIN_VALUE leg: MIN + MAX + 1 = 0 in exact arithmetic
            // with no intermediate overflow. Its reversal needs −MIN_VALUE, which does not
            // exist in 64 bits — negateExact throws instead of returning MIN_VALUE again
            // (ADR-0001: overflow never wraps; the caller maps this to amount-overflow).
            EntryDraft draft = new EntryDraft(null, List.of(
                    leg(CASH, Long.MIN_VALUE, EUR),
                    leg(REVENUE, Long.MAX_VALUE, EUR),
                    leg(VAT, 1, EUR)));
            JournalEntry original = JournalEntry.post(ENTRY, EntryType.JOURNAL, draft, null,
                    "alice", null, T0, List.of(P1, P2, P3));
            assertThatThrownBy(() -> JournalEntry.reversalDraftOf(original, null))
                    .isInstanceOf(ArithmeticException.class);
        }
    }
}
