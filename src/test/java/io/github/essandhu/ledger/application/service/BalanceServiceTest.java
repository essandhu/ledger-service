package io.github.essandhu.ledger.application.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.essandhu.ledger.application.port.in.AccountNotFound;
import io.github.essandhu.ledger.application.port.in.BalanceView;
import io.github.essandhu.ledger.application.port.in.StatementCursor;
import io.github.essandhu.ledger.application.port.in.StatementFilter;
import io.github.essandhu.ledger.application.port.in.StatementPage;
import io.github.essandhu.ledger.application.port.in.StatementSpec;
import io.github.essandhu.ledger.domain.error.AmountOverflow;
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
import io.github.essandhu.ledger.support.fakes.FakeJournalRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-0002's read split over fakes: current = the snapshot (lock-free), as-of = derived from
 * postings and NEVER the snapshot, plus the M3 statement contract — (from, to] window, keyset
 * order with BYTEWISE id ties, and the always-resumable next cursor. The SQL semantics
 * themselves are the persistence adapter's integration tests; the fakes reproduce them so the
 * service's orchestration is provable without Spring (TEST-STRATEGY §2.1).
 */
@DisplayName("BalanceService: ADR-0002 read split and the M3 statement contract")
class BalanceServiceTest {

    private static final Instant T0 = Instant.parse("2026-07-22T10:00:00Z");
    private static final CurrencyCode EUR = new CurrencyCode("EUR");

    // Same bytewise-vs-signed trap as PostingServiceTest: HIGH's first bit is set, so signed
    // UUID.compareTo sorts it FIRST while PostgreSQL (and the statement keyset) sort it LAST.
    private static final UUID MAIN = UUID.fromString("019817b4-0000-7000-8000-0000000000aa");
    private static final UUID OTHER = UUID.fromString("019817b4-0000-7000-8000-0000000000bb");

    private static final UUID E1 = UUID.fromString("019817b5-0000-7000-8000-0000000000e1");
    private static final UUID E2 = UUID.fromString("019817b5-0000-7000-8000-0000000000e2");
    private static final UUID E3 = UUID.fromString("019817b5-0000-7000-8000-0000000000e3");
    private static final UUID P_LOW = UUID.fromString("019817b5-0000-7000-8000-0000000000f1");
    private static final UUID P_MID = UUID.fromString("019817b5-0000-7000-8000-0000000000f2");
    private static final UUID P_HIGH = UUID.fromString("f9817b50-0000-7000-8000-0000000000f3");
    private static final UUID P_A = UUID.fromString("019817b5-0000-7000-8000-0000000000a1");
    private static final UUID P_B = UUID.fromString("019817b5-0000-7000-8000-0000000000a2");
    private static final UUID P_C = UUID.fromString("019817b5-0000-7000-8000-0000000000a3");

    private final FakeAccountRepository accounts = new FakeAccountRepository();
    private final FakeBalanceRepository balances = new FakeBalanceRepository();
    private final FakeJournalRepository journal = new FakeJournalRepository();

    private final BalanceService service = new BalanceService(accounts, balances, journal);

    private AccountId seededAccount(UUID id, AccountType type) {
        AccountId accountId = new AccountId(id);
        accounts.seed(Account.open(accountId, "balance-fixture-" + id, EUR, type, true, T0));
        return accountId;
    }

    /** A balanced two-leg entry: {@code amount} on {@code main}, the negation on {@code other}. */
    private void seededEntry(UUID entryId, Instant postedAt, AccountId main, long amount,
            AccountId other, UUID mainPostingId, UUID otherPostingId) {
        EntryDraft draft = new EntryDraft("m3-fixture", List.of(
                new EntryDraft.Leg(main, Money.of(amount, EUR)),
                new EntryDraft.Leg(other, Money.of(-amount, EUR))));
        journal.seed(JournalEntry.post(new EntryId(entryId), EntryType.JOURNAL, draft, null,
                "balance-tester", postedAt,
                List.of(new PostingId(mainPostingId), new PostingId(otherPostingId))));
    }

    // ── current: the snapshot, lock-free ───────────────────────────────────────────────────

    @Test
    @DisplayName("current: snapshot verbatim — raw, natural = raw × direction, postingCount, no asOf")
    void current_reads_the_snapshot() {
        AccountId cash = seededAccount(MAIN, AccountType.ASSET);
        balances.seed(new AccountBalance(cash, 250, 3, T0));

        BalanceView view = service.current(cash);

        assertThat(view.raw()).isEqualTo(250);
        assertThat(view.natural()).isEqualTo(250);
        assertThat(view.postingCount()).isEqualTo(3);
        assertThat(view.currency()).isEqualTo(EUR);
        assertThat(view.type()).isEqualTo(AccountType.ASSET);
        assertThat(view.asOf()).isEmpty();
        assertThat(balances.lockInvocations()).as("reads take no lock (ADR-0003)").isEmpty();
    }

    @Test
    @DisplayName("PLAN §4.2: a credit-normal account's natural balance flips the raw sign")
    void current_flips_sign_for_credit_normal_accounts() {
        AccountId wallet = seededAccount(MAIN, AccountType.LIABILITY);
        balances.seed(new AccountBalance(wallet, -250, 1, T0));

        BalanceView view = service.current(wallet);

        assertThat(view.raw()).isEqualTo(-250);
        assertThat(view.natural()).isEqualTo(250);
    }

    @Test
    @DisplayName("current: unknown account → the 404 AccountNotFound (path-addressed miss)")
    void current_unknown_account_is_not_found() {
        assertThatThrownBy(() -> service.current(new AccountId(MAIN)))
                .isInstanceOf(AccountNotFound.class);
    }

    @Test
    @DisplayName("current: an account without a snapshot row is corruption, not a 404")
    void current_missing_snapshot_row_fails_loudly() {
        AccountId orphan = seededAccount(MAIN, AccountType.ASSET);

        assertThatThrownBy(() -> service.current(orphan))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("V2 forward-contract");
    }

    @Test
    @DisplayName("ADR-0001: raw Long.MIN_VALUE on a credit-normal account has no 64-bit natural → 422 AmountOverflow")
    void current_natural_overflow_is_translated() {
        AccountId wallet = seededAccount(MAIN, AccountType.LIABILITY);
        balances.seed(new AccountBalance(wallet, Long.MIN_VALUE, 1, T0));

        assertThatThrownBy(() -> service.current(wallet))
                .isInstanceOf(AmountOverflow.class)
                .hasMessageContaining("checked arithmetic rejects, never wraps");
    }

    @Test
    @DisplayName("PLAN §4.5: FROZEN and CLOSED accounts still serve balance reads")
    void current_serves_frozen_and_closed_accounts() {
        AccountId frozen = new AccountId(MAIN);
        accounts.seed(Account.open(frozen, "balance-frozen", EUR, AccountType.ASSET, true, T0)
                .transitionTo(AccountStatus.FROZEN, T0));
        balances.seed(new AccountBalance(frozen, 42, 1, T0));

        assertThat(service.current(frozen).natural()).isEqualTo(42);
    }

    // ── as-of: derived from postings, never the snapshot ───────────────────────────────────

    @Test
    @DisplayName("I10: asOf sums postings with posted_at <= at — the cut is INCLUSIVE")
    void as_of_sums_postings_up_to_the_inclusive_cut() {
        AccountId main = seededAccount(MAIN, AccountType.ASSET);
        AccountId other = seededAccount(OTHER, AccountType.EQUITY);
        Instant t1 = T0;
        Instant t2 = T0.plus(10, ChronoUnit.MICROS);
        seededEntry(E1, t1, main, 100, other, P_A, P_B);
        seededEntry(E2, t2, main, 40, other, P_C, P_LOW);

        BalanceView atT1 = service.asOf(main, t1);
        BalanceView atT2 = service.asOf(main, t2);

        assertThat(atT1.raw()).isEqualTo(100);
        assertThat(atT1.postingCount()).isEqualTo(1);
        assertThat(atT1.asOf()).contains(t1);
        assertThat(atT2.raw()).isEqualTo(140); // boundary posting included
        assertThat(atT2.postingCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("ADR-0002: asOf NEVER reads the snapshot — a corrupted snapshot cannot leak into it")
    void as_of_ignores_the_snapshot() {
        AccountId main = seededAccount(MAIN, AccountType.ASSET);
        AccountId other = seededAccount(OTHER, AccountType.EQUITY);
        balances.seed(new AccountBalance(main, 999_999, 42, T0)); // deliberately wrong
        seededEntry(E1, T0, main, 100, other, P_A, P_B);

        assertThat(service.asOf(main, T0).raw()).isEqualTo(100);
    }

    @Test
    @DisplayName("as-of: unknown account → the 404 AccountNotFound, even though the sum would be 0")
    void as_of_unknown_account_is_not_found() {
        assertThatThrownBy(() -> service.asOf(new AccountId(MAIN), T0))
                .isInstanceOf(AccountNotFound.class);
    }

    // ── statement: (from, to] window, keyset order, always-resumable cursor ────────────────

    @Test
    @DisplayName("statement: lines order by posted_at then BYTEWISE id — PostgreSQL's order, not UUID.compareTo's")
    void statement_orders_id_ties_bytewise() {
        AccountId main = seededAccount(MAIN, AccountType.ASSET);
        // One entry, both legs on MAIN ({+100, −100} balances to zero): the two postings
        // share posted_at, so ONLY the id tiebreak orders them. Positionally the +100 leg
        // gets P_HIGH (first bit set): signed ordering would return it first — bytewise
        // (correct) returns P_MID first.
        EntryDraft draft = new EntryDraft("m3-fixture", List.of(
                new EntryDraft.Leg(main, Money.of(100, EUR)),
                new EntryDraft.Leg(main, Money.of(-100, EUR))));
        journal.seed(JournalEntry.post(new EntryId(E1), EntryType.JOURNAL, draft, null,
                "balance-tester", T0, List.of(new PostingId(P_HIGH), new PostingId(P_MID))));

        StatementPage page = service.statement(main, StatementFilter.unbounded(),
                StatementSpec.firstPage(10));

        assertThat(page.lines()).extracting(posting -> posting.id().value())
                .containsExactly(P_MID, P_HIGH);
        assertThat(page.lines()).extracting(posting -> posting.amount().amount())
                .containsExactly(-100L, 100L);
    }

    @Test
    @DisplayName("PLAN §5 (pinned at M3): the window is (from, to] — from exclusive, to inclusive")
    void statement_window_is_from_exclusive_to_inclusive() {
        AccountId main = seededAccount(MAIN, AccountType.ASSET);
        AccountId other = seededAccount(OTHER, AccountType.EQUITY);
        Instant t1 = T0;
        Instant t2 = T0.plus(10, ChronoUnit.MICROS);
        Instant t3 = T0.plus(20, ChronoUnit.MICROS);
        seededEntry(E1, t1, main, 1, other, P_A, P_LOW);
        seededEntry(E2, t2, main, 2, other, P_B, P_MID);
        seededEntry(E3, t3, main, 4, other, P_C, P_HIGH);

        StatementPage page = service.statement(main,
                new StatementFilter(Optional.of(t1), Optional.of(t2)),
                StatementSpec.firstPage(10));

        assertThat(page.lines()).extracting(posting -> posting.amount().amount())
                .containsExactly(2L); // t1 excluded, t2 included, t3 outside
    }

    @Test
    @DisplayName("statement: resumes strictly after the cursor; next = the last returned line's position")
    void statement_resumes_strictly_after_the_cursor() {
        AccountId main = seededAccount(MAIN, AccountType.ASSET);
        AccountId other = seededAccount(OTHER, AccountType.EQUITY);
        seededEntry(E1, T0, main, 1, other, P_A, P_LOW);
        seededEntry(E2, T0.plus(1, ChronoUnit.MICROS), main, 2, other, P_B, P_MID);
        seededEntry(E3, T0.plus(2, ChronoUnit.MICROS), main, 4, other, P_C, P_HIGH);

        StatementPage first = service.statement(main, StatementFilter.unbounded(),
                StatementSpec.firstPage(2));
        assertThat(first.lines()).extracting(posting -> posting.amount().amount())
                .containsExactly(1L, 2L);
        Posting lastOfFirst = first.lines().get(1);
        assertThat(first.next()).contains(
                new StatementCursor(lastOfFirst.postedAt(), lastOfFirst.id()));

        StatementPage second = service.statement(main, StatementFilter.unbounded(),
                new StatementSpec(first.next(), 2));
        assertThat(second.lines()).extracting(posting -> posting.amount().amount())
                .containsExactly(4L); // no duplicate of line 2, no skip of line 3
    }

    @Test
    @DisplayName("statement (pinned at M3): an empty page echoes the request's cursor — tail-following is stateless")
    void statement_empty_page_echoes_the_request_cursor() {
        AccountId main = seededAccount(MAIN, AccountType.ASSET);
        AccountId other = seededAccount(OTHER, AccountType.EQUITY);
        seededEntry(E1, T0, main, 1, other, P_A, P_LOW);
        StatementCursor caughtUp = new StatementCursor(T0, new PostingId(P_A));

        StatementPage page = service.statement(main, StatementFilter.unbounded(),
                new StatementSpec(Optional.of(caughtUp), 10));

        assertThat(page.lines()).isEmpty();
        assertThat(page.next()).contains(caughtUp);
    }

    @Test
    @DisplayName("statement: a cursor-less walk of an empty account has no cursor — genesis IS \"no cursor\"")
    void statement_empty_first_page_has_no_cursor() {
        AccountId main = seededAccount(MAIN, AccountType.ASSET);

        StatementPage page = service.statement(main, StatementFilter.unbounded(),
                StatementSpec.firstPage(10));

        assertThat(page.lines()).isEmpty();
        assertThat(page.next()).isEmpty();
    }

    @Test
    @DisplayName("statement: unknown account → the 404 AccountNotFound (never an empty 200)")
    void statement_unknown_account_is_not_found() {
        assertThatThrownBy(() -> service.statement(new AccountId(MAIN),
                StatementFilter.unbounded(), StatementSpec.firstPage(10)))
                .isInstanceOf(AccountNotFound.class);
    }
}
