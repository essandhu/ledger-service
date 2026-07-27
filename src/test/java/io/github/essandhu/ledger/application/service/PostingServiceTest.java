package io.github.essandhu.ledger.application.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.github.essandhu.ledger.application.port.in.CanonicalCommand;
import io.github.essandhu.ledger.application.port.in.EntryNotFound;
import io.github.essandhu.ledger.application.port.in.IdempotencyKeyConflict;
import io.github.essandhu.ledger.application.port.in.PostJournalEntryUseCase.PostEntryCommand;
import io.github.essandhu.ledger.application.port.in.PostingOutcome;
import io.github.essandhu.ledger.application.port.in.ReverseEntryUseCase.ReverseCommand;
import io.github.essandhu.ledger.application.port.in.TransferFundsUseCase.TransferCommand;
import io.github.essandhu.ledger.application.port.out.IdempotencyRecord;
import io.github.essandhu.ledger.application.port.out.IdempotencyRepository;
import io.github.essandhu.ledger.application.port.out.WriteResponseRenderer;
import io.github.essandhu.ledger.domain.error.AccountClosed;
import io.github.essandhu.ledger.domain.error.AccountFrozen;
import io.github.essandhu.ledger.domain.error.AmountOverflow;
import io.github.essandhu.ledger.domain.error.CurrencyMismatch;
import io.github.essandhu.ledger.domain.error.EntryAlreadyReversed;
import io.github.essandhu.ledger.domain.error.OverdraftViolation;
import io.github.essandhu.ledger.domain.error.UnbalancedEntry;
import io.github.essandhu.ledger.domain.error.UnknownPostingAccount;
import io.github.essandhu.ledger.domain.model.Account;
import io.github.essandhu.ledger.domain.model.AccountBalance;
import io.github.essandhu.ledger.domain.model.AccountId;
import io.github.essandhu.ledger.domain.model.AccountStatus;
import io.github.essandhu.ledger.domain.model.AccountType;
import io.github.essandhu.ledger.domain.model.CurrencyCode;
import io.github.essandhu.ledger.domain.model.EntryDraft;
import io.github.essandhu.ledger.domain.model.EntryId;
import io.github.essandhu.ledger.domain.model.EntryType;
import io.github.essandhu.ledger.domain.model.JournalEntry;
import io.github.essandhu.ledger.domain.model.Money;
import io.github.essandhu.ledger.domain.model.Posting;
import io.github.essandhu.ledger.domain.model.PostingId;
import io.github.essandhu.ledger.support.fakes.FakeAccountRepository;
import io.github.essandhu.ledger.support.fakes.FakeBalanceRepository;
import io.github.essandhu.ledger.support.fakes.FakeIdempotencyRepository;
import io.github.essandhu.ledger.support.fakes.FakeJournalRepository;
import io.github.essandhu.ledger.support.fakes.FixedIdGenerator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The pinned critical section over fakes (ADR-0003 §Decision): draft validation
 * before any I/O, canonically ordered locking, post-lock-only status reads, checked overdraft
 * arithmetic, posted_at under the lock — and the ADR-0004 contract that a rejected posting
 * writes NOTHING (the fakes count inserts and deltas so "nothing" is a hard assertion, not an
 * absence of evidence). The FOR UPDATE semantics themselves are the persistence adapter's
 * integration tests; here the fakes enforce the port contracts (order, distinctness) instead.
 */
@DisplayName("PostingService: the pinned posting protocol (ADR-0003)")
class PostingServiceTest {

    private static final Instant T0 = Instant.parse("2026-07-22T10:00:00Z");
    private static final CurrencyCode EUR = new CurrencyCode("EUR");
    private static final CurrencyCode USD = new CurrencyCode("USD");

    // Account ids chosen to prove BYTEWISE (unsigned) canonical ordering: HIGH's first bit is
    // set, so signed UUID.compareTo would sort it FIRST — bytewise (= PostgreSQL order) it is
    // LAST. A service sorting with the wrong comparator fails the lock-order assertion.
    private static final UUID LOW = UUID.fromString("019817b4-0000-7000-8000-0000000000aa");
    private static final UUID MID = UUID.fromString("019817b4-0000-7000-8000-0000000000bb");
    private static final UUID HIGH = UUID.fromString("f9817b40-0000-7000-8000-0000000000cc");

    private static final UUID ENTRY = UUID.fromString("019817b5-0000-7000-8000-0000000000e1");
    private static final UUID P1 = UUID.fromString("019817b5-0000-7000-8000-0000000000f1");
    private static final UUID P2 = UUID.fromString("019817b5-0000-7000-8000-0000000000f2");
    private static final UUID P3 = UUID.fromString("019817b5-0000-7000-8000-0000000000f3");
    private static final UUID P4 = UUID.fromString("019817b5-0000-7000-8000-0000000000f4");

    private static final UUID ORIGINAL = UUID.fromString("019817b5-0000-7000-8000-0000000000b1");
    private static final UUID OP1 = UUID.fromString("019817b5-0000-7000-8000-0000000000a1");
    private static final UUID OP2 = UUID.fromString("019817b5-0000-7000-8000-0000000000a2");

    /** The key every canned command posts under (M4) — one logical operation per test. */
    private static final String IDEM_KEY = "posting-service-test-key";
    private static final Duration TTL = Duration.ofDays(90);

    private final FakeAccountRepository accounts = new FakeAccountRepository();
    private final FakeJournalRepository journal = new FakeJournalRepository();
    private final FakeBalanceRepository balances = new FakeBalanceRepository();
    private final FakeIdempotencyRepository idempotencyStore = new FakeIdempotencyRepository();

    /** Deterministic stand-in for the web renderer: what matters here is that EXACTLY these
     * bytes come back on replay, not what real rendering looks like. */
    private static final WriteResponseRenderer RENDERER = entry ->
            new WriteResponseRenderer.Rendered(201, "{\"id\":\"" + entry.id().value() + "\"}");

    private PostingService service() {
        return new PostingService(accounts, journal, balances, idempotencyStore, RENDERER,
                new FixedIdGenerator(ENTRY, P1, P2, P3, P4), Clock.fixed(T0, ZoneOffset.UTC),
                TTL);
    }

    /**
     * The same-key race, made deterministic: a store whose records become visible only on the
     * SECOND read. The first read is the pre-lock fast path (the winner is still uncommitted
     * there); every later read models "the winner committed while this request waited on the
     * balance locks" — READ COMMITTED statement visibility, compressed into a fake.
     */
    private PostingService serviceSeeingRecordsOnSecondLook() {
        IdempotencyRepository secondLook = new IdempotencyRepository() {
            private int finds;

            @Override
            public java.util.Optional<IdempotencyRecord> find(String createdBy, String key) {
                finds++;
                return finds == 1 ? java.util.Optional.empty()
                        : idempotencyStore.find(createdBy, key);
            }

            @Override
            public void insert(IdempotencyRecord record) {
                idempotencyStore.insert(record);
            }

            @Override
            public int deleteExpiredBatch(Instant cutoff, int batchSize) {
                return idempotencyStore.deleteExpiredBatch(cutoff, batchSize);
            }
        };
        return new PostingService(accounts, journal, balances, secondLook, RENDERER,
                new FixedIdGenerator(ENTRY, P1, P2, P3, P4), Clock.fixed(T0, ZoneOffset.UTC),
                TTL);
    }

    /** Unwraps the fresh-post outcome (the pre-M4 tests' subject); a replay here is a failure. */
    private static JournalEntry posted(PostingOutcome outcome) {
        assertThat(outcome).isInstanceOf(PostingOutcome.Posted.class);
        return ((PostingOutcome.Posted) outcome).entry();
    }

    private AccountId seeded(UUID id, AccountType type, boolean allowNegative,
            CurrencyCode currency, long rawBalance) {
        AccountId accountId = new AccountId(id);
        accounts.seed(Account.open(accountId, "posting-fixture-" + id, currency, type,
                allowNegative, T0));
        balances.seed(new AccountBalance(accountId, rawBalance, 0, T0));
        return accountId;
    }

    /** Shorthand for the common case: every single-currency scenario runs in EUR. */
    private static EntryDraft.Leg leg(AccountId accountId, long amount) {
        return new EntryDraft.Leg(accountId, Money.of(amount, EUR));
    }

    private static PostEntryCommand entryOf(EntryDraft.Leg... legs) {
        return new PostEntryCommand("test entry", List.of(legs), "posting-tester", IDEM_KEY);
    }

    private JournalEntry seededOriginal(AccountId debit, AccountId credit) {
        EntryDraft draft = new EntryDraft("original", List.of(leg(debit, 100), leg(credit, -100)));
        JournalEntry original = JournalEntry.post(new EntryId(ORIGINAL), EntryType.JOURNAL, draft,
                null, "posting-tester", null, T0, List.of(new PostingId(OP1), new PostingId(OP2)));
        journal.seed(original);
        return original;
    }

    /** ADR-0004 (sweep item 33): a rejected posting writes NOTHING — no entry, no deltas,
     * and (M4) no idempotency record, so a retry re-executes against clean state. */
    private void assertNothingWritten() {
        assertThat(journal.insertCalls()).as("journal inserts after rejection").isZero();
        assertThat(balances.appliedDeltas()).as("balance deltas after rejection").isEmpty();
        assertThat(idempotencyStore.insertCalls()).as("idempotency records after rejection").isZero();
    }

    @Test
    @DisplayName("post: a balanced multi-leg entry becomes header + positional postings + per-account deltas")
    void posts_balanced_entry_and_applies_deltas() {
        AccountId cash = seeded(LOW, AccountType.ASSET, false, EUR, 250);
        AccountId equity = seeded(MID, AccountType.EQUITY, false, EUR, 0);

        JournalEntry entry = posted(service().postEntry(entryOf(leg(cash, 100), leg(equity, -100))));

        assertThat(entry.id()).isEqualTo(new EntryId(ENTRY));
        assertThat(entry.entryType()).isEqualTo(EntryType.JOURNAL);
        assertThat(entry.description()).isEqualTo("test entry");
        assertThat(entry.createdBy()).isEqualTo("posting-tester");
        assertThat(entry.idempotencyKey()).as("ADR-0004: the key rides on the entry forever")
                .isEqualTo(IDEM_KEY);
        assertThat(entry.postings()).extracting(Posting::id)
                .containsExactly(new PostingId(P1), new PostingId(P2));
        assertThat(entry.postings()).extracting(Posting::accountId).containsExactly(cash, equity);
        assertThat(journal.findById(entry.id())).contains(entry);
        assertThat(balances.appliedDeltas()).containsExactly(
                new FakeBalanceRepository.AppliedDelta(cash, 100, 1, T0),
                new FakeBalanceRepository.AppliedDelta(equity, -100, 1, T0));
        assertThat(balances.balanceOf(cash).orElseThrow().balance()).isEqualTo(350);
        assertThat(balances.balanceOf(equity).orElseThrow().balance()).isEqualTo(-100);
    }

    @Test
    @DisplayName("I1: an unbalanced draft is rejected before ANY repository interaction — validation is pure")
    void unbalanced_draft_rejected_before_any_repository_interaction() {
        AccountId cash = seeded(LOW, AccountType.ASSET, false, EUR, 250);
        AccountId equity = seeded(MID, AccountType.EQUITY, false, EUR, 0);
        PostingService service = service();

        assertThatThrownBy(() -> service.postEntry(entryOf(leg(cash, 100), leg(equity, -50))))
                .isInstanceOf(UnbalancedEntry.class);

        assertThat(balances.lockInvocations()).as("no lock before a valid draft").isEmpty();
        assertNothingWritten();
    }

    @Test
    @DisplayName("ADR-0003: the lock is taken once, over DISTINCT account ids in canonical bytewise UUID order")
    void lock_taken_with_canonically_sorted_distinct_ids() {
        AccountId high = seeded(HIGH, AccountType.ASSET, false, EUR, 0);
        AccountId low = seeded(LOW, AccountType.ASSET, false, EUR, 100);
        AccountId mid = seeded(MID, AccountType.ASSET, false, EUR, 100);

        // Shuffled input, LOW twice — the service must dedupe and sort bytewise: HIGH's set
        // sign bit would make a signed sort put it first; canonical (PostgreSQL) order is last.
        service().postEntry(entryOf(leg(high, 100), leg(low, -30), leg(mid, -40), leg(low, -30)));

        assertThat(balances.lockInvocations()).containsExactly(List.of(low, mid, high));
    }

    @Test
    @DisplayName("unknown-account: a missing snapshot row under the lock is the 422, never a 404")
    void unknown_account_detected_from_lock_result() {
        AccountId cash = seeded(LOW, AccountType.ASSET, false, EUR, 250);
        AccountId ghost = new AccountId(MID); // never seeded: no account, no balance row
        PostingService service = service();

        assertThatThrownBy(() -> service.postEntry(entryOf(leg(cash, 100), leg(ghost, -100))))
                .isInstanceOf(UnknownPostingAccount.class)
                .extracting("accountIds").isEqualTo(java.util.Set.of(ghost));

        assertThat(balances.lockInvocations()).as("detected FROM the lock result").hasSize(1);
        assertNothingWritten();
    }

    @Test
    @DisplayName("corruption guard: a locked balance row whose account row is missing fails loudly, naming the FK — never an NPE")
    void balance_row_without_account_row_fails_loudly_not_npe() {
        AccountId cash = seeded(LOW, AccountType.ASSET, false, EUR, 250);
        // A balance row with NO account row is unreachable through any code path
        // (account_balance carries a FK to account), so the fixture seeds the corruption
        // directly into the balance fake alone. The guard must file it as corruption with the
        // suspect FK named, not fall through to an NPE at the status check three lines later.
        AccountId orphan = new AccountId(MID);
        balances.seed(new AccountBalance(orphan, 0, 0, T0));
        PostingService service = service();

        assertThatThrownBy(() -> service.postEntry(entryOf(leg(cash, 100), leg(orphan, -100))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("account_balance FK")
                .hasMessageContaining(orphan.value().toString());

        assertThat(balances.lockInvocations())
                .as("the guard runs UNDER the lock — the orphan passed the step-3 existence check")
                .hasSize(1);
        assertNothingWritten();
    }

    @Test
    @DisplayName("currency mismatch: a leg's currency must match its account's")
    void leg_currency_must_match_account_currency() {
        AccountId cash = seeded(LOW, AccountType.ASSET, false, EUR, 250);
        AccountId equity = seeded(MID, AccountType.EQUITY, false, EUR, 0);
        PostingService service = service();

        PostEntryCommand usdLegs = new PostEntryCommand("test entry", List.of(
                new EntryDraft.Leg(cash, Money.of(100, USD)),
                new EntryDraft.Leg(equity, Money.of(-100, USD))),
                "posting-tester", IDEM_KEY);
        assertThatThrownBy(() -> service.postEntry(usdLegs))
                .isInstanceOf(CurrencyMismatch.class)
                .hasFieldOrPropertyWithValue("expected", EUR)
                .hasFieldOrPropertyWithValue("actual", USD);
        assertNothingWritten();
    }

    @Test
    @DisplayName("I12 (posting half): a FROZEN account rejects postings, and nothing is written")
    void frozen_account_rejects_postings() {
        AccountId frozen = seeded(LOW, AccountType.ASSET, false, EUR, 250);
        accounts.seed(accounts.findById(frozen).orElseThrow()
                .transitionTo(AccountStatus.FROZEN, T0));
        AccountId equity = seeded(MID, AccountType.EQUITY, false, EUR, 0);
        PostingService service = service();

        assertThatThrownBy(() -> service.postEntry(entryOf(leg(frozen, 100), leg(equity, -100))))
                .isInstanceOf(AccountFrozen.class);
        assertNothingWritten();
    }

    @Test
    @DisplayName("I12 (posting half): a CLOSED account rejects postings, and nothing is written")
    void closed_account_rejects_postings() {
        AccountId closed = seeded(LOW, AccountType.ASSET, false, EUR, 250);
        accounts.seed(accounts.findById(closed).orElseThrow()
                .transitionTo(AccountStatus.CLOSED, T0));
        AccountId equity = seeded(MID, AccountType.EQUITY, false, EUR, 0);
        PostingService service = service();

        assertThatThrownBy(() -> service.postEntry(entryOf(leg(closed, 100), leg(equity, -100))))
                .isInstanceOf(AccountClosed.class);
        assertNothingWritten();
    }

    @Test
    @DisplayName("I6: overdrawing a debit-normal account is rejected with the would-be natural balance")
    void overdraft_on_debit_normal_account() {
        AccountId cash = seeded(LOW, AccountType.ASSET, false, EUR, 100);
        AccountId sink = seeded(MID, AccountType.EQUITY, true, EUR, 0);
        PostingService service = service();

        assertThatThrownBy(() -> service.postEntry(entryOf(leg(cash, -150), leg(sink, 150))))
                .isInstanceOfSatisfying(OverdraftViolation.class, violation -> {
                    assertThat(violation.accountId()).isEqualTo(cash);
                    assertThat(violation.attemptedNaturalBalance())
                            .as("the would-be NATURAL balance, sign intact — the client's fix-it number")
                            .isEqualTo(-50L);
                });
        assertNothingWritten();
    }

    @Test
    @DisplayName("I6: multiple legs on the same account are summed before the overdraft judgment")
    void overdraft_sums_multiple_legs_on_same_account() {
        AccountId cash = seeded(LOW, AccountType.ASSET, false, EUR, 100);
        AccountId sink = seeded(MID, AccountType.EQUITY, true, EUR, 0);
        PostingService service = service();

        // Net on cash: +200 − 350 = −150 → raw 100 − 150 = −50. Judged as one delta, not leg
        // by leg — leg-by-leg would reject the −350 midway or accept the +200 alone.
        assertThatThrownBy(() -> service.postEntry(
                entryOf(leg(cash, 200), leg(cash, -350), leg(sink, 150))))
                .isInstanceOf(OverdraftViolation.class)
                .hasFieldOrPropertyWithValue("attemptedNaturalBalance", -50L);
        assertNothingWritten();
    }

    @Test
    @DisplayName("I6: the judgment reads the NATURAL balance (raw × direction), not the raw one")
    void overdraft_judges_natural_not_raw() {
        // LIABILITY at raw −50 is natural +50 (value owed). A +60 debit takes raw to +10 —
        // POSITIVE raw, but natural −10: the liability would owe the world negative value.
        AccountId loan = seeded(LOW, AccountType.LIABILITY, false, EUR, -50);
        AccountId cash = seeded(MID, AccountType.ASSET, true, EUR, 0);
        PostingService service = service();

        assertThatThrownBy(() -> service.postEntry(entryOf(leg(loan, 60), leg(cash, -60))))
                .isInstanceOf(OverdraftViolation.class)
                .hasFieldOrPropertyWithValue("accountId", loan)
                .hasFieldOrPropertyWithValue("attemptedNaturalBalance", -10L);
        assertNothingWritten();
    }

    @Test
    @DisplayName("I6: an allow_negative account may go negative without complaint")
    void allow_negative_account_goes_negative() {
        AccountId overdraftable = seeded(LOW, AccountType.ASSET, true, EUR, 0);
        AccountId cash = seeded(MID, AccountType.ASSET, false, EUR, 0);

        service().postEntry(entryOf(leg(overdraftable, -100), leg(cash, 100)));

        assertThat(balances.balanceOf(overdraftable).orElseThrow().balance()).isEqualTo(-100);
        assertThat(balances.balanceOf(cash).orElseThrow().balance()).isEqualTo(100);
    }

    @Test
    @DisplayName("ADR-0001: a new balance outside 64-bit minor units rejects with AmountOverflow, never wraps")
    void new_balance_overflow_rejects() {
        AccountId brimming = seeded(LOW, AccountType.ASSET, true, EUR, Long.MAX_VALUE);
        AccountId sink = seeded(MID, AccountType.EQUITY, true, EUR, 0);
        PostingService service = service();

        assertThatThrownBy(() -> service.postEntry(entryOf(leg(brimming, 1), leg(sink, -1))))
                .isInstanceOf(AmountOverflow.class);
        assertNothingWritten();
    }

    @Test
    @DisplayName("ADR-0001: a per-account delta outside 64-bit minor units rejects — the single-UPDATE bump needs a representable delta")
    void per_account_delta_overflow_rejects() {
        AccountId a = seeded(LOW, AccountType.ASSET, true, EUR, 0);
        AccountId b = seeded(MID, AccountType.EQUITY, true, EUR, 0);
        PostingService service = service();

        // Balanced per currency (interleaved, so the draft's running sum never overflows) but
        // account a's net delta is Long.MAX_VALUE + 1 — unrepresentable, so unapplicable.
        assertThatThrownBy(() -> service.postEntry(entryOf(
                leg(a, Long.MAX_VALUE), leg(b, -Long.MAX_VALUE), leg(a, 1), leg(b, -1))))
                .isInstanceOf(AmountOverflow.class);
        assertNothingWritten();
    }

    @Test
    @DisplayName("ADR-0001: the natural-balance judgment at raw Long.MIN_VALUE rejects with AmountOverflow, never a wrapped sign")
    void natural_balance_overflow_rejects_not_misjudges() {
        // Raw (MIN_VALUE + 100) − 100 = Long.MIN_VALUE, whose × direction(LIABILITY) = −1 has
        // no 64-bit product. Unchecked, the wrap would report natural Long.MIN_VALUE — a
        // NEGATIVE number for an astronomically positive liability position — and misfile the
        // rejection as OverdraftViolation. Checked multiply files it truthfully: AmountOverflow.
        AccountId liability = seeded(LOW, AccountType.LIABILITY, false, EUR, Long.MIN_VALUE + 100);
        AccountId sink = seeded(MID, AccountType.ASSET, true, EUR, 0);
        PostingService service = service();

        assertThatThrownBy(() -> service.postEntry(entryOf(leg(liability, -100), leg(sink, 100))))
                .isInstanceOf(AmountOverflow.class);
        assertNothingWritten();
    }

    @Test
    @DisplayName("The time model: posted_at comes from the injected Clock, read under the lock, shared by header, legs, and deltas")
    void posted_at_comes_from_the_clock() {
        AccountId cash = seeded(LOW, AccountType.ASSET, false, EUR, 0);
        AccountId equity = seeded(MID, AccountType.EQUITY, false, EUR, 0);

        JournalEntry entry = posted(service().postEntry(entryOf(leg(cash, 100), leg(equity, -100))));

        assertThat(entry.postedAt()).isEqualTo(T0);
        assertThat(entry.postings()).extracting(Posting::postedAt).containsOnly(T0);
        assertThat(balances.appliedDeltas())
                .extracting(FakeBalanceRepository.AppliedDelta::now).containsOnly(T0);
    }

    @Test
    @DisplayName("The time model: posted_at is clamped strictly above a touched account's last posted_at — a backwards wall clock cannot reorder an account's postings")
    void posted_at_never_regresses_behind_a_touched_accounts_last_posting() {
        AccountId cash = seeded(LOW, AccountType.ASSET, false, EUR, 0);
        AccountId equity = seeded(MID, AccountType.EQUITY, false, EUR, 0);
        // The account already has posting history "in the future" of the wall clock: its last
        // posting landed 5µs after T0, but the clock now reads T0 (an NTP step back — the same
        // threat MonotoneUuidClock guards ids against). Without the clamp this entry would
        // sort BEFORE the earlier one, and an M3 keyset cursor past it would skip it forever.
        Instant lastPostedAt = T0.plus(5, ChronoUnit.MICROS);
        balances.seed(new AccountBalance(cash, 0, 3, lastPostedAt));

        JournalEntry entry = posted(service().postEntry(entryOf(leg(cash, 100), leg(equity, -100))));

        Instant clamped = lastPostedAt.plus(1, ChronoUnit.MICROS);
        assertThat(entry.postedAt()).isEqualTo(clamped);
        assertThat(entry.postings()).extracting(Posting::postedAt).containsOnly(clamped);
        assertThat(balances.appliedDeltas())
                .extracting(FakeBalanceRepository.AppliedDelta::now).containsOnly(clamped);
    }

    @Test
    @DisplayName("The time model: the posted_at clamp takes the strictest floor across ALL touched accounts")
    void posted_at_clamp_takes_the_max_across_touched_accounts() {
        AccountId cash = seeded(LOW, AccountType.ASSET, false, EUR, 0);
        AccountId equity = seeded(MID, AccountType.EQUITY, false, EUR, 0);
        balances.seed(new AccountBalance(cash, 0, 1, T0.plus(2, ChronoUnit.MICROS)));
        balances.seed(new AccountBalance(equity, 0, 1, T0.plus(7, ChronoUnit.MICROS)));

        JournalEntry entry = posted(service().postEntry(entryOf(leg(cash, 100), leg(equity, -100))));

        assertThat(entry.postedAt()).isEqualTo(T0.plus(8, ChronoUnit.MICROS));
    }

    @Test
    @DisplayName("The time model: when the wall clock is already ahead of every floor, posted_at is the clock reading")
    void posted_at_uses_the_clock_when_ahead_of_all_floors() {
        AccountId cash = seeded(LOW, AccountType.ASSET, false, EUR, 0);
        AccountId equity = seeded(MID, AccountType.EQUITY, false, EUR, 0);
        balances.seed(new AccountBalance(cash, 0, 4, T0.minus(3, ChronoUnit.MICROS)));

        JournalEntry entry = posted(service().postEntry(entryOf(leg(cash, 100), leg(equity, -100))));

        assertThat(entry.postedAt()).isEqualTo(T0);
    }

    @Test
    @DisplayName("The time model: an account's FIRST posting is not clamped — updated_at is creation time then, not posting history")
    void first_posting_is_not_clamped_by_account_creation_time() {
        // Fresh seeds carry posting_count = 0 with updated_at = creation time. The floor
        // exists to order postings among THEMSELVES (keyset/as-of correctness); a posting at
        // the account's creation instant is harmless, so no bump on first use.
        AccountId cash = seeded(LOW, AccountType.ASSET, false, EUR, 0);
        AccountId equity = seeded(MID, AccountType.EQUITY, false, EUR, 0);

        JournalEntry entry = posted(service().postEntry(entryOf(leg(cash, 100), leg(equity, -100))));

        assertThat(entry.postedAt()).isEqualTo(T0);
    }

    @Test
    @DisplayName("The API contract, literally: transfer source is the DEBIT (+amount), target the CREDIT (−amount)")
    void transfer_builds_debit_source_credit_target() {
        AccountId source = seeded(LOW, AccountType.ASSET, false, EUR, 0);
        AccountId target = seeded(MID, AccountType.ASSET, true, EUR, 0);

        JournalEntry entry = posted(service().transfer(new TransferCommand(source, target,
                Money.of(100, EUR), "move it", "posting-tester", IDEM_KEY)));

        assertThat(entry.entryType()).isEqualTo(EntryType.TRANSFER);
        assertThat(entry.postings()).hasSize(2);
        assertThat(entry.postings().get(0).accountId()).isEqualTo(source);
        assertThat(entry.postings().get(0).amount().amount()).isEqualTo(100);
        assertThat(entry.postings().get(1).accountId()).isEqualTo(target);
        assertThat(entry.postings().get(1).amount().amount()).isEqualTo(-100);
    }

    @Test
    @DisplayName("ADR-0001: a transfer of Long.MIN_VALUE rejects as AmountOverflow — negateExact's lone impossible input, translated at the accumulation point")
    void transfer_of_long_min_value_rejects_as_amount_overflow() {
        AccountId source = seeded(LOW, AccountType.ASSET, true, EUR, 0);
        AccountId target = seeded(MID, AccountType.ASSET, true, EUR, 0);
        PostingService service = service();
        // Money imposes no range restriction, so MIN_VALUE reaches the target-leg negation —
        // where −Long.MIN_VALUE has no 64-bit representation. The service must translate the
        // refusal into the 422, not leak the raw ArithmeticException as a 500.
        TransferCommand command = new TransferCommand(source, target,
                Money.of(Long.MIN_VALUE, EUR), null, "posting-tester", IDEM_KEY);

        assertThatThrownBy(() -> service.transfer(command))
                .isInstanceOf(AmountOverflow.class)
                .hasMessageContaining("transfer amount has no 64-bit negation");

        assertThat(balances.lockInvocations())
                .as("rejected while building the draft, before any lock").isEmpty();
        assertNothingWritten();
    }

    @Test
    @DisplayName("I11: a reversal negates every leg exactly, positionally, linked via reversalOf")
    void reversal_negates_every_leg_exactly() {
        AccountId cash = seeded(LOW, AccountType.ASSET, false, EUR, 100);
        AccountId equity = seeded(MID, AccountType.EQUITY, false, EUR, -100);
        JournalEntry original = seededOriginal(cash, equity);

        JournalEntry reversal = posted(service().reverse(
                new ReverseCommand(original.id(), "undo", "posting-tester", IDEM_KEY)));

        assertThat(reversal.entryType()).isEqualTo(EntryType.REVERSAL);
        assertThat(reversal.reversalOf()).isEqualTo(original.id());
        assertThat(reversal.postings()).extracting(Posting::accountId)
                .containsExactly(cash, equity);
        assertThat(reversal.postings()).extracting(p -> p.amount().amount())
                .containsExactly(-100L, 100L);
        assertThat(balances.balanceOf(cash).orElseThrow().balance()).isZero();
        assertThat(balances.balanceOf(equity).orElseThrow().balance()).isZero();
    }

    @Test
    @DisplayName("ADR-0001: reversing an entry with a Long.MIN_VALUE leg rejects as AmountOverflow naming the original — never a raw ArithmeticException")
    void reversal_of_long_min_value_leg_rejects_as_amount_overflow() {
        // The MIN_VALUE leg is constructible through the FRONT DOOR: no two-leg entry can
        // balance it (−Long.MIN_VALUE does not exist), but three legs can — MIN_VALUE +
        // MAX_VALUE = −1, then −1 + 1 = 0, every partial sum representable IN THIS LEG ORDER,
        // so the draft's checked accumulation accepts what no negation ever could. ASSET keeps
        // natural = raw (direction +1) and allowNegative skips the overdraft floor, so the
        // legs land without tripping any other overflow guard.
        AccountId sink = seeded(LOW, AccountType.ASSET, true, EUR, 0);
        AccountId brimming = seeded(MID, AccountType.ASSET, true, EUR, 0);
        AccountId penny = seeded(HIGH, AccountType.ASSET, true, EUR, 0);
        PostingService service = service();
        JournalEntry original = posted(service.postEntry(entryOf(
                leg(sink, Long.MIN_VALUE), leg(brimming, Long.MAX_VALUE), leg(penny, 1))));

        // A fresh key: the reversal is its own logical operation — reusing the post's key
        // would surface as the hash-mismatch conflict, not the negation edge under test.
        ReverseCommand reverse = new ReverseCommand(original.id(), null, "posting-tester",
                IDEM_KEY + "-reversal");
        assertThatThrownBy(() -> service.reverse(reverse))
                .isInstanceOf(AmountOverflow.class)
                .hasMessageContaining("reversal of entry " + original.id().value())
                .hasMessageContaining("no 64-bit negation");

        assertThat(journal.insertCalls()).as("the original stands alone").isEqualTo(1);
        assertThat(balances.lockInvocations())
                .as("rejected while negating the draft — the reversal never reached the lock")
                .hasSize(1);
        assertThat(idempotencyStore.insertCalls()).as("no record for a rejection").isEqualTo(1);
    }

    @Test
    @DisplayName("I11: a second reversal is rejected INSIDE the locked section, and writes nothing")
    void second_reversal_rejected_inside_the_lock() {
        AccountId cash = seeded(LOW, AccountType.ASSET, false, EUR, 100);
        AccountId equity = seeded(MID, AccountType.EQUITY, false, EUR, -100);
        JournalEntry original = seededOriginal(cash, equity);
        journal.seed(JournalEntry.post(new EntryId(P4), EntryType.REVERSAL,
                JournalEntry.reversalDraftOf(original, null), original.id(), "posting-tester",
                null, T0, List.of(new PostingId(OP1), new PostingId(OP2))));
        PostingService service = service();

        assertThatThrownBy(() -> service.reverse(
                new ReverseCommand(original.id(), null, "posting-tester", IDEM_KEY)))
                .isInstanceOfSatisfying(EntryAlreadyReversed.class, error ->
                        assertThat(error.originalId())
                                .as("the 422 names the ORIGINAL — the existing reversal is the fix, not a retry")
                                .isEqualTo(original.id()));

        // The at-most-once check must run under the lock (the lock serializes the race; the
        // partial unique index is only the backstop) — so the lock WAS acquired first.
        assertThat(balances.lockInvocations()).hasSize(1);
        assertNothingWritten();
    }

    @Test
    @DisplayName("reverse: an unknown original is EntryNotFound (path id → 404) — no lock is taken")
    void reverse_unknown_entry_is_not_found() {
        PostingService service = service();
        ReverseCommand command = new ReverseCommand(new EntryId(ORIGINAL), null, "posting-tester",
                IDEM_KEY);

        assertThatThrownBy(() -> service.reverse(command)).isInstanceOf(EntryNotFound.class);

        assertThat(balances.lockInvocations()).isEmpty();
        assertNothingWritten();
    }

    @Test
    @DisplayName("get: returns the entry; unknown id raises EntryNotFound")
    void get_returns_entry_or_not_found() {
        AccountId cash = seeded(LOW, AccountType.ASSET, false, EUR, 100);
        AccountId equity = seeded(MID, AccountType.EQUITY, false, EUR, -100);
        JournalEntry original = seededOriginal(cash, equity);
        PostingService service = service();

        assertThat(service.byId(original.id())).isEqualTo(original);
        EntryId unknown = new EntryId(P4);
        assertThatThrownBy(() -> service.byId(unknown)).isInstanceOf(EntryNotFound.class);
    }

    @Nested
    @DisplayName("M4 idempotency (ADR-0004): replay, conflict, same-transaction record")
    class Idempotency {

        @Test
        @DisplayName("I8 (serial): the same command again replays the stored response — nothing executed, not even a lock")
        void replay_returns_stored_response_and_executes_nothing() {
            AccountId cash = seeded(LOW, AccountType.ASSET, false, EUR, 0);
            AccountId equity = seeded(MID, AccountType.EQUITY, false, EUR, 0);
            PostingService service = service();
            PostEntryCommand command = entryOf(leg(cash, 100), leg(equity, -100));
            JournalEntry first = posted(service.postEntry(command));

            PostingOutcome second = service.postEntry(command);

            assertThat(second).isInstanceOf(PostingOutcome.Replayed.class);
            assertThat(((PostingOutcome.Replayed) second).responseBody())
                    .as("the stored original response, byte for byte")
                    .isEqualTo("{\"id\":\"" + first.id().value() + "\"}");
            assertThat(journal.insertCalls()).as("one entry ever").isEqualTo(1);
            assertThat(idempotencyStore.insertCalls()).as("one record ever").isEqualTo(1);
            assertThat(balances.appliedDeltas()).as("balances moved exactly once").hasSize(2);
            assertThat(balances.lockInvocations())
                    .as("a replay takes NO balance locks — it never enters the critical section")
                    .hasSize(1);
        }

        @Test
        @DisplayName("I9: same key, different payload → IdempotencyKeyConflict, zero side effects")
        void different_payload_under_same_key_conflicts_with_zero_side_effects() {
            AccountId cash = seeded(LOW, AccountType.ASSET, false, EUR, 0);
            AccountId equity = seeded(MID, AccountType.EQUITY, false, EUR, 0);
            PostingService service = service();
            posted(service.postEntry(entryOf(leg(cash, 100), leg(equity, -100))));
            int insertsAfterFirst = journal.insertCalls();
            PostEntryCommand tampered = entryOf(leg(cash, 999), leg(equity, -999));

            assertThatThrownBy(() -> service.postEntry(tampered))
                    .isInstanceOf(IdempotencyKeyConflict.class)
                    .hasFieldOrPropertyWithValue("idempotencyKey", IDEM_KEY);

            assertThat(journal.insertCalls()).isEqualTo(insertsAfterFirst);
            assertThat(idempotencyStore.insertCalls()).isEqualTo(1);
            assertThat(balances.appliedDeltas()).as("balances moved exactly once").hasSize(2);
        }

        @Test
        @DisplayName("ADR-0004: the record is the command's canonical hash + the rendered response, stamped with the entry's own instant and TTL")
        void record_carries_hash_response_and_expiry() {
            AccountId cash = seeded(LOW, AccountType.ASSET, false, EUR, 0);
            AccountId equity = seeded(MID, AccountType.EQUITY, false, EUR, 0);
            PostEntryCommand command = entryOf(leg(cash, 100), leg(equity, -100));

            JournalEntry entry = posted(service().postEntry(command));

            IdempotencyRecord record = idempotencyStore.find("posting-tester", IDEM_KEY)
                    .orElseThrow();
            assertThat(record.requestHash()).isEqualTo(CanonicalCommand.hash(command));
            assertThat(record.entryId()).isEqualTo(entry.id());
            assertThat(record.responseStatus()).isEqualTo(201);
            assertThat(record.responseBody()).isEqualTo("{\"id\":\"" + entry.id().value() + "\"}");
            assertThat(record.createdAt())
                    .as("one transaction, one instant: the entry's own postedAt")
                    .isEqualTo(entry.postedAt());
            assertThat(record.expiresAt()).isEqualTo(entry.postedAt().plus(TTL));
        }

        @Test
        @DisplayName("ADR-0004: the verdict precedes validation — a recorded key replays even a payload the domain would reject")
        void replay_short_circuits_before_draft_validation() {
            // An UNBALANCED command: only a pre-validation replay can answer it with anything
            // but UnbalancedEntry. Seed its own hash as already recorded — the retry-after-
            // response-loss shape, where the stored verdict must win over re-execution.
            AccountId cash = seeded(LOW, AccountType.ASSET, false, EUR, 0);
            PostEntryCommand unbalanced = entryOf(leg(cash, 100), leg(cash, -50));
            idempotencyStore.seed(new IdempotencyRecord("posting-tester", IDEM_KEY,
                    CanonicalCommand.hash(unbalanced), new EntryId(ORIGINAL), 201,
                    "{\"stored\":true}", T0, T0.plus(TTL)));

            PostingOutcome outcome = service().postEntry(unbalanced);

            assertThat(outcome).isInstanceOf(PostingOutcome.Replayed.class);
            assertThat(((PostingOutcome.Replayed) outcome).responseBody())
                    .isEqualTo("{\"stored\":true}");
            assertThat(balances.lockInvocations()).isEmpty();
            assertNothingWritten();
        }

        @Test
        @DisplayName("ADR-0004: a replayed reversal answers from the record before the 404 lookup can miss")
        void reversal_replay_precedes_original_lookup() {
            ReverseCommand command = new ReverseCommand(new EntryId(ORIGINAL), null,
                    "posting-tester", IDEM_KEY); // ORIGINAL is never seeded — a fresh attempt 404s
            idempotencyStore.seed(new IdempotencyRecord("posting-tester", IDEM_KEY,
                    CanonicalCommand.hash(command), new EntryId(P4), 201,
                    "{\"stored\":\"reversal\"}", T0, T0.plus(TTL)));

            PostingOutcome outcome = service().reverse(command);

            assertThat(outcome).isInstanceOf(PostingOutcome.Replayed.class);
            assertThat(((PostingOutcome.Replayed) outcome).responseBody())
                    .isEqualTo("{\"stored\":\"reversal\"}");
        }

        @Test
        @DisplayName("ADR-0004: only successes are recorded — after a rejection, the SAME key legitimately retries and posts")
        void rejected_attempt_leaves_key_unclaimed_for_a_corrected_retry() {
            AccountId cash = seeded(LOW, AccountType.ASSET, false, EUR, 0);
            AccountId equity = seeded(MID, AccountType.EQUITY, false, EUR, 0);
            PostingService service = service();

            assertThatThrownBy(() -> service.postEntry(entryOf(leg(cash, 100), leg(equity, -50))))
                    .isInstanceOf(UnbalancedEntry.class);
            assertNothingWritten();

            // The obstacle removed (balanced now), the same key re-executes and succeeds —
            // pinning a 422 forever would block exactly this recovery (ADR-0004 §Mechanics).
            JournalEntry entry = posted(service.postEntry(
                    entryOf(leg(cash, 100), leg(equity, -100))));
            assertThat(entry.idempotencyKey()).isEqualTo(IDEM_KEY);
            assertThat(idempotencyStore.insertCalls()).isEqualTo(1);
        }

        @Test
        @DisplayName("race, settled under the lock: a duplicate reversal replays the winner's record — never entry-already-reversed")
        void race_loser_reversal_replays_under_the_lock_instead_of_domain_422() {
            AccountId cash = seeded(LOW, AccountType.ASSET, false, EUR, 100);
            AccountId equity = seeded(MID, AccountType.EQUITY, false, EUR, -100);
            JournalEntry original = seededOriginal(cash, equity);
            // The winner's committed reversal: without the under-lock re-read, the loser
            // would re-run reversalExistsFor against this state and answer 422 for an
            // operation that SUCCEEDED — the deterministic misfiling the M4 review caught.
            journal.seed(JournalEntry.post(new EntryId(P4), EntryType.REVERSAL,
                    JournalEntry.reversalDraftOf(original, null), original.id(),
                    "posting-tester", null, T0, List.of(new PostingId(OP1), new PostingId(OP2))));
            ReverseCommand command = new ReverseCommand(original.id(), null, "posting-tester",
                    IDEM_KEY);
            // The winner's record becomes visible only on the SECOND read — i.e. after the
            // loser stopped waiting on the balance locks, exactly the race interleaving.
            idempotencyStore.seed(new IdempotencyRecord("posting-tester", IDEM_KEY,
                    CanonicalCommand.hash(command), new EntryId(P4), 201,
                    "{\"winner\":\"reversal\"}", T0, T0.plus(TTL)));

            PostingOutcome outcome = serviceSeeingRecordsOnSecondLook().reverse(command);

            assertThat(outcome).isInstanceOf(PostingOutcome.Replayed.class);
            assertThat(((PostingOutcome.Replayed) outcome).responseBody())
                    .isEqualTo("{\"winner\":\"reversal\"}");
            assertThat(balances.lockInvocations())
                    .as("the verdict landed UNDER the lock — the lock was taken").hasSize(1);
            assertThat(journal.insertCalls()).isZero();
        }

        @Test
        @DisplayName("race, settled under the lock: a duplicate transfer that would now overdraft replays — never overdraft")
        void race_loser_transfer_replays_under_the_lock_instead_of_overdraft() {
            // The winner already drained the strict TARGET (the credited, −amount side per
            // the API contract) to raw 0; re-judging the duplicate against that state would compute
            // natural −60 and file the SUCCEEDED transfer as an overdraft — and per
            // ADR-0004's option-2b analysis, a client told its transfer failed re-sends under
            // a fresh key, which double-posts.
            AccountId source = seeded(LOW, AccountType.ASSET, true, EUR, 60);
            AccountId target = seeded(MID, AccountType.ASSET, false, EUR, 0);
            TransferCommand command = new TransferCommand(source, target, Money.of(60, EUR),
                    null, "posting-tester", IDEM_KEY);
            idempotencyStore.seed(new IdempotencyRecord("posting-tester", IDEM_KEY,
                    CanonicalCommand.hash(command), new EntryId(P4), 201,
                    "{\"winner\":\"transfer\"}", T0, T0.plus(TTL)));

            PostingOutcome outcome = serviceSeeingRecordsOnSecondLook().transfer(command);

            assertThat(outcome).isInstanceOf(PostingOutcome.Replayed.class);
            assertThat(((PostingOutcome.Replayed) outcome).responseBody())
                    .isEqualTo("{\"winner\":\"transfer\"}");
            assertThat(journal.insertCalls()).isZero();
            assertThat(balances.appliedDeltas()).isEmpty();
        }

        @Test
        @DisplayName("ADR-0004 option 3b: a purged key replays from the PERMANENT entry — reconstructed body, no re-execution")
        void purged_key_replays_from_the_permanent_entry() {
            AccountId cash = seeded(LOW, AccountType.ASSET, false, EUR, 100);
            AccountId equity = seeded(MID, AccountType.EQUITY, false, EUR, -100);
            // The entry survived (kept forever); its idempotency record did not (purged).
            JournalEntry settled = JournalEntry.post(new EntryId(ORIGINAL), EntryType.JOURNAL,
                    new EntryDraft("test entry", List.of(leg(cash, 100), leg(equity, -100))),
                    null, "posting-tester", IDEM_KEY, T0,
                    List.of(new PostingId(OP1), new PostingId(OP2)));
            journal.seed(settled);

            PostingOutcome outcome = service().postEntry(
                    entryOf(leg(cash, 100), leg(equity, -100)));

            assertThat(outcome).isInstanceOf(PostingOutcome.Replayed.class);
            assertThat(((PostingOutcome.Replayed) outcome).responseBody())
                    .as("the body is RECONSTRUCTED from the entry (may differ from the "
                            + "original bytes — the documented degradation)")
                    .isEqualTo("{\"id\":\"" + ORIGINAL + "\"}");
            assertThat(balances.lockInvocations()).as("settled before any lock").isEmpty();
            assertThat(journal.insertCalls()).isZero();
            assertThat(idempotencyStore.insertCalls())
                    .as("degraded replay does not re-materialize the record").isZero();
        }

        @Test
        @DisplayName("ADR-0004 option 3b, the documented loss: after a purge, a DIFFERENT payload under the old key also replays (discrimination went with the record)")
        void purged_key_reuse_with_different_payload_replays_not_conflicts() {
            AccountId cash = seeded(LOW, AccountType.ASSET, false, EUR, 100);
            AccountId equity = seeded(MID, AccountType.EQUITY, false, EUR, -100);
            journal.seed(JournalEntry.post(new EntryId(ORIGINAL), EntryType.JOURNAL,
                    new EntryDraft("test entry", List.of(leg(cash, 100), leg(equity, -100))),
                    null, "posting-tester", IDEM_KEY, T0,
                    List.of(new PostingId(OP1), new PostingId(OP2))));

            // A tampered reuse would deserve the 422 — but the hash went with the record, so
            // the old entry comes back as a replay. Money stays safe; diagnostics degrade —
            // exactly the trade option 3b records (and option 3c's silent double-post is what
            // this fallback exists to prevent).
            PostingOutcome outcome = service().postEntry(
                    entryOf(leg(cash, 999), leg(equity, -999)));

            assertThat(outcome).isInstanceOf(PostingOutcome.Replayed.class);
            assertThat(journal.insertCalls()).isZero();
        }

        @Test
        @DisplayName("ADR-0004 scope 1b: the same key under a DIFFERENT principal is a different scope — both post")
        void same_key_different_principal_is_a_different_scope() {
            AccountId cash = seeded(LOW, AccountType.ASSET, false, EUR, 0);
            AccountId equity = seeded(MID, AccountType.EQUITY, false, EUR, 0);
            PostingService service = service();
            posted(service.postEntry(entryOf(leg(cash, 100), leg(equity, -100))));

            // Different createdBy, same key, same payload: not a replay, not a conflict —
            // per-principal keyspaces are what makes cross-tenant leakage structurally
            // impossible (a shared keyspace would replay ANOTHER principal's stored response).
            PostingOutcome other = new PostingService(accounts, journal, balances,
                    idempotencyStore, RENDERER, new FixedIdGenerator(P3, OP1, OP2),
                    Clock.fixed(T0, ZoneOffset.UTC), TTL)
                    .postEntry(new PostEntryCommand("test entry",
                            List.of(leg(cash, 100), leg(equity, -100)), "other-tester", IDEM_KEY));

            assertThat(other).isInstanceOf(PostingOutcome.Posted.class);
            assertThat(journal.insertCalls()).isEqualTo(2);
            assertThat(idempotencyStore.insertCalls()).isEqualTo(2);
        }
    }
}
