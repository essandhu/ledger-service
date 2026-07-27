package io.github.essandhu.ledger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import io.github.essandhu.ledger.application.port.in.CreateAccountUseCase;
import io.github.essandhu.ledger.application.port.in.CreateAccountUseCase.CreateAccountCommand;
import io.github.essandhu.ledger.application.port.in.EntryNotFound;
import io.github.essandhu.ledger.application.port.in.PostJournalEntryUseCase;
import io.github.essandhu.ledger.application.port.in.PostJournalEntryUseCase.PostEntryCommand;
import io.github.essandhu.ledger.application.port.in.PostingOutcome;
import io.github.essandhu.ledger.application.port.in.ReverseEntryUseCase;
import io.github.essandhu.ledger.application.port.in.ReverseEntryUseCase.ReverseCommand;
import io.github.essandhu.ledger.application.port.in.TransferFundsUseCase;
import io.github.essandhu.ledger.application.port.in.TransferFundsUseCase.TransferCommand;
import io.github.essandhu.ledger.application.port.in.UpdateAccountUseCase;
import io.github.essandhu.ledger.application.port.in.UpdateAccountUseCase.UpdateAccountCommand;
import io.github.essandhu.ledger.domain.error.OverdraftViolation;
import io.github.essandhu.ledger.domain.model.AccountId;
import io.github.essandhu.ledger.domain.model.AccountStatus;
import io.github.essandhu.ledger.domain.model.AccountType;
import io.github.essandhu.ledger.domain.model.CurrencyCode;
import io.github.essandhu.ledger.domain.model.EntryDraft;
import io.github.essandhu.ledger.domain.model.EntryId;
import io.github.essandhu.ledger.domain.model.Money;
import io.github.essandhu.ledger.support.LedgerIntegrationTest;
import io.github.essandhu.ledger.support.model.ModelLedger;
import io.github.essandhu.ledger.support.model.ModelLedger.Verdict;
import io.github.essandhu.ledger.support.property.Gen;
import io.github.essandhu.ledger.support.property.Iterations;
import io.github.essandhu.ledger.support.property.Property;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The M5 stateful model-vs-SUT suite (ADR-0005's command-sequence runner; ADR-0003 §Proof):
 * randomized sequences of post / transfer / freeze / close / reverse commands run against BOTH
 * a sequential {@link ModelLedger} and the real service — real use-case beans behind their
 * {@code @PreAuthorize}/{@code @Transactional} proxies, real locks, real PostgreSQL — and
 * after EVERY command the verdicts and the full observable state (status, snapshot balance,
 * posting_count per account, entry count for the run's principal) must agree exactly. The
 * point: the intricate machinery — canonical locking, under-lock status reads, snapshot
 * maintenance — must produce ordinary sequential bookkeeping semantics, decidable by ~150
 * lines of plain Java; any divergence fails with the generated command sequence as the
 * counterexample. (Reported UNSHRUNK, by the harness's own contract: shrinking covers
 * numeric/list shapes only, and a Scenario is a record — seed replay, not minimization, is
 * the reproduction story here; ADR-0005: improve the generator, not the shrinker.)
 *
 * <p>House DB-property discipline (the sibling *PropertyIntegrationTest suites): fresh
 * accounts and a fresh principal per iteration, so every iteration judges clean state;
 * scenario generation is a pure function of the rng ({@code -Dledger.property.seed}
 * replays); idempotency keys are run-unique UUIDs (rng-derived keys would REPLAY on seed
 * replay instead of posting); reduced default iterations for the PostgreSQL round-trips; all
 * assertions scoped to the iteration's own rows.
 */
@LedgerIntegrationTest
@DisplayName("ADR-0005 (M5): stateful model-vs-SUT (I4) — the service agrees with a sequential in-memory ledger after every command")
class StatefulModelPropertyIntegrationTest {

    private static final String REDUCED_ITERATIONS = "25";
    private static final List<CurrencyCode> CURRENCIES =
            List.of(new CurrencyCode("EUR"), new CurrencyCode("JPY"));

    @Autowired
    private CreateAccountUseCase createAccount;

    @Autowired
    private PostJournalEntryUseCase postEntry; // the @Primary metered decorator, like production

    @Autowired
    private TransferFundsUseCase transferFunds;

    @Autowired
    private ReverseEntryUseCase reverseEntry;

    @Autowired
    private UpdateAccountUseCase updateAccount;

    @Autowired
    private DataSource dataSource;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // --- the generated scenario: account specs + a command sequence over account INDEXES ---
    // Indexes, not ids, because generation must be a pure function of the rng while the real
    // account ids are only born at execution time (the app generates UUIDv7s inside the
    // create-account transaction) — the scenario can never reference an id it hasn't caused.

    private record AccountSpec(int currencyPick, int typePick, boolean allowNegative) {
    }

    private sealed interface Cmd {
    }

    /** Two-leg transfer: source index, target index (may collide — a same-account zero-delta
     * entry is legal and locks like any other), amount, and WHICH account's currency the money
     * is denominated in — picking another account's currency generates real mismatches. */
    private record Transfer(int src, int tgt, long amount, int currencyOf) implements Cmd {
    }

    /** Explicit two-leg journal entry: debit index, credit index, amount, currency pick. */
    private record Journal(int debit, int credit, long amount, int currencyOf) implements Cmd {
    }

    /** Lifecycle transition — ACTIVE ⇄ FROZEN edges, CLOSED attempts, same-state no-ops. */
    private record SetStatus(int account, AccountStatus target) implements Cmd {
    }

    /** Reverse the {@code ordinal % posted}-th accepted entry; {@code unknownTarget} instead
     * aims at an entry id that provably does not exist (the 404 family). */
    private record Reverse(int ordinal, boolean unknownTarget) implements Cmd {
    }

    private record Scenario(List<AccountSpec> accounts, List<Cmd> commands) {
    }

    private static Gen<Scenario> scenarios() {
        Gen<AccountSpec> specs = Gen.ints(0, CURRENCIES.size() - 1).flatMap(currency ->
                Gen.ints(0, AccountType.values().length - 1).flatMap(type ->
                        Gen.oneOf(Gen.constant(false), Gen.constant(true)).map(allowNegative ->
                                new AccountSpec(currency, type, allowNegative))));
        return specs.listOf(2, 4).flatMap(accounts -> {
            Gen<Integer> index = Gen.ints(0, accounts.size() - 1);
            Gen<Long> amounts = Gen.longs(1, 300);
            Gen<Cmd> transfer = index.flatMap(src -> index.flatMap(tgt ->
                    amounts.flatMap(amount -> index.map(currencyOf ->
                            new Transfer(src, tgt, amount, currencyOf)))));
            Gen<Cmd> journal = index.flatMap(debit -> index.flatMap(credit ->
                    amounts.flatMap(amount -> index.map(currencyOf ->
                            new Journal(debit, credit, amount, currencyOf)))));
            Gen<Cmd> setStatus = index.flatMap(account ->
                    Gen.oneOf(Gen.constant(AccountStatus.ACTIVE),
                                    Gen.constant(AccountStatus.FROZEN),
                                    Gen.constant(AccountStatus.CLOSED))
                            .map(target -> new SetStatus(account, target)));
            // Ordinals biased hard toward 0..2: entry counts stay small (4-10 commands), so
            // small ordinals make two Reverse commands COLLIDE on the same entry often —
            // that collision is the only road to EntryAlreadyReversed, and an unbiased
            // 0..15 draw reaches it in barely half of 25-iteration runs (measured by
            // simulation during review); the bias lifts it to near-certainty per run.
            Gen<Cmd> reverse = Gen.frequency(
                            new Gen.Weighted<>(3, Gen.ints(0, 2)),
                            new Gen.Weighted<>(1, Gen.ints(0, 15)))
                    .flatMap(ordinal ->
                            Gen.frequency(
                                            new Gen.Weighted<>(7, Gen.constant(false)),
                                            new Gen.Weighted<>(1, Gen.constant(true)))
                                    .map(unknown -> new Reverse(ordinal, unknown)));
            return Gen.frequency(
                            new Gen.Weighted<>(4, transfer),
                            new Gen.Weighted<>(3, journal),
                            new Gen.Weighted<>(3, setStatus),
                            new Gen.Weighted<>(3, reverse))
                    .listOf(4, 10)
                    .map(commands -> new Scenario(accounts, commands));
        });
    }

    @Test
    @DisplayName("model and SUT agree on every verdict, every balance, every status, after every command")
    void service_agrees_with_the_sequential_model() {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
                "model-prop-suite", "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_LEDGER_ADMIN"),
                        new SimpleGrantedAuthority("ROLE_LEDGER_WRITE"))));

        Iterations.withReducedDefault(REDUCED_ITERATIONS, () -> Property.check(scenarios(), scenario -> {
            // Fresh principal and fresh accounts per iteration: every iteration judges clean
            // state, and every DB assertion below scopes to these rows only.
            String subject = "model-prop-" + UUID.randomUUID();
            ModelLedger model = new ModelLedger();
            List<AccountId> ids = new ArrayList<>();
            for (AccountSpec spec : scenario.accounts()) {
                CurrencyCode currency = CURRENCIES.get(spec.currencyPick());
                AccountType type = AccountType.values()[spec.typePick()];
                AccountId id = createAccount.create(new CreateAccountCommand(
                        "model-prop-" + UUID.randomUUID(), currency, type,
                        spec.allowNegative())).id();
                model.open(id, currency, type, spec.allowNegative());
                ids.add(id);
            }
            List<EntryId> sutEntries = new ArrayList<>();

            for (Cmd cmd : scenario.commands()) {
                Verdict expected;
                Verdict actual;
                switch (cmd) {
                    case Transfer t -> {
                        Money money = Money.of(t.amount(),
                                model.account(ids.get(t.currencyOf())).currency());
                        expected = model.post(ModelLedger.transferLegs(
                                ids.get(t.src()), ids.get(t.tgt()), money));
                        actual = posting(() -> transferFunds.transfer(new TransferCommand(
                                ids.get(t.src()), ids.get(t.tgt()), money, null,
                                subject, freshKey())), sutEntries);
                    }
                    case Journal j -> {
                        Money money = Money.of(j.amount(),
                                model.account(ids.get(j.currencyOf())).currency());
                        List<EntryDraft.Leg> legs = List.of(
                                new EntryDraft.Leg(ids.get(j.debit()), money),
                                new EntryDraft.Leg(ids.get(j.credit()), money.negate()));
                        expected = model.post(legs);
                        actual = posting(() -> postEntry.postEntry(new PostEntryCommand(
                                null, legs, subject, freshKey())), sutEntries);
                    }
                    case SetStatus s -> {
                        expected = model.updateStatus(ids.get(s.account()), s.target());
                        actual = lifecycle(() -> updateAccount.update(new UpdateAccountCommand(
                                ids.get(s.account()), Optional.empty(),
                                Optional.of(s.target()))));
                    }
                    case Reverse r -> {
                        if (r.unknownTarget() || model.postedEntryCount() == 0) {
                            // A version-0 UUID: no app-generated (UUIDv7) entry can carry it,
                            // so the SUT must answer the pre-lock 404 — nothing else.
                            EntryId ghost = new EntryId(new UUID(0L, r.ordinal() + 1L));
                            expected = Verdict.rejected(EntryNotFound.class);
                            actual = posting(() -> reverseEntry.reverse(new ReverseCommand(
                                    ghost, null, subject, freshKey())), sutEntries);
                        } else {
                            int index = r.ordinal() % model.postedEntryCount();
                            expected = model.reverse(index);
                            actual = posting(() -> reverseEntry.reverse(new ReverseCommand(
                                    sutEntries.get(index), null, subject, freshKey())),
                                    sutEntries);
                        }
                    }
                }

                assertThat(actual.rejection())
                        .as("verdict for %s (model expected %s)", cmd, expected)
                        .isEqualTo(expected.rejection());
                if (expected.rejection() == OverdraftViolation.class) {
                    assertThat(actual.offender())
                            .as("the overdraft names the canonical-order FIRST offender for %s", cmd)
                            .isEqualTo(expected.offender());
                }

                // Full observable state after every step, from database truth: lifecycle
                // status, snapshot balance, and posting_count for every account of this run,
                // plus the principal's entry count — nothing may drift, even after rejections
                // (a rejected command writes NOTHING, in both worlds).
                for (AccountId id : ids) {
                    ModelLedger.ModelAccount expectedAccount = model.account(id);
                    AccountRow actualRow = accountRow(id);
                    assertThat(actualRow)
                            .as("state of %s after %s", id.value(), cmd)
                            .isEqualTo(new AccountRow(expectedAccount.status().name(),
                                    expectedAccount.balance(),
                                    expectedAccount.postingCount()));
                }
                assertThat(entryCount(subject))
                        .as("entries recorded for this principal after %s", cmd)
                        .isEqualTo(model.postedEntryCount());
            }
        }));
    }

    /** Run-unique on purpose — see the class javadoc's seed-replay note. */
    private static String freshKey() {
        return UUID.randomUUID().toString();
    }

    /** Classifies a money-mover call the way the model speaks: accepted (appending the new
     * entry id so later reversals can address it) or the rejection's class — the overdraft
     * keeping its offender for the first-offender comparison. */
    private static Verdict posting(Supplier<PostingOutcome> call, List<EntryId> sink) {
        try {
            PostingOutcome outcome = call.get();
            // Fresh key per command: a Replayed here would itself be a divergence.
            assertThat(outcome).isInstanceOf(PostingOutcome.Posted.class);
            sink.add(((PostingOutcome.Posted) outcome).entry().id());
            return Verdict.ACCEPTED;
        } catch (OverdraftViolation overdraft) {
            return new Verdict(OverdraftViolation.class, overdraft.accountId());
        } catch (RuntimeException rejection) {
            return Verdict.rejected(rejection.getClass());
        }
    }

    private static Verdict lifecycle(Supplier<?> call) {
        try {
            call.get();
            return Verdict.ACCEPTED;
        } catch (RuntimeException rejection) {
            return Verdict.rejected(rejection.getClass());
        }
    }

    private record AccountRow(String status, long balance, long postingCount) {
    }

    private AccountRow accountRow(AccountId id) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement("""
                     SELECT a.status, b.balance, b.posting_count
                     FROM account a JOIN account_balance b ON b.account_id = a.id
                     WHERE a.id = ?
                     """)) {
            select.setObject(1, id.value());
            try (ResultSet row = select.executeQuery()) {
                assertThat(row.next()).as("account row exists for " + id.value()).isTrue();
                return new AccountRow(row.getString("status"), row.getLong("balance"),
                        row.getLong("posting_count"));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private long entryCount(String subject) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT count(*) FROM journal_entry WHERE created_by = ?")) {
            select.setString(1, subject);
            try (ResultSet row = select.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
