package io.github.essandhu.ledger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import io.github.essandhu.ledger.application.port.in.BalanceView;
import io.github.essandhu.ledger.application.port.in.CreateAccountUseCase;
import io.github.essandhu.ledger.application.port.in.CreateAccountUseCase.CreateAccountCommand;
import io.github.essandhu.ledger.application.port.in.GetBalanceQuery;
import io.github.essandhu.ledger.application.port.in.GetStatementQuery;
import io.github.essandhu.ledger.application.port.in.PostJournalEntryUseCase;
import io.github.essandhu.ledger.application.port.in.PostJournalEntryUseCase.PostEntryCommand;
import io.github.essandhu.ledger.application.port.in.PostingOutcome;
import io.github.essandhu.ledger.application.port.in.StatementFilter;
import io.github.essandhu.ledger.application.port.in.StatementPage;
import io.github.essandhu.ledger.application.port.in.StatementSpec;
import io.github.essandhu.ledger.domain.model.AccountId;
import io.github.essandhu.ledger.domain.model.AccountType;
import io.github.essandhu.ledger.domain.model.CurrencyCode;
import io.github.essandhu.ledger.domain.model.EntryDraft;
import io.github.essandhu.ledger.domain.model.JournalEntry;
import io.github.essandhu.ledger.domain.model.Money;
import io.github.essandhu.ledger.domain.model.Posting;
import io.github.essandhu.ledger.support.LedgerIntegrationTest;
import io.github.essandhu.ledger.support.property.Gen;
import io.github.essandhu.ledger.support.property.Iterations;
import io.github.essandhu.ledger.support.property.Property;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * I10, end to end (TEST-STRATEGY §3): for generated entry sequences posted through the REAL
 * stack, {@code asOf(t2) − asOf(t1) = Σ postings in (t1, t2]} per account, {@code asOf(now)}
 * equals the current snapshot (this suite is single-writer, i.e. quiesced), and the keyset
 * statement walk at a random page size visits exactly the account's postings, in order,
 * without duplicates — with the walk's Σ and count reconciling against the balance views.
 * The window boundaries are the entries' OWN posted_at instants (the sharpest possible cuts:
 * exactness at the boundary is the half of I10 a wall-clock-sampled test could miss).
 *
 * <p>The generator deliberately includes same-account {+X, −X} leg pairs: both legs share the
 * entry's posted_at, so every instant cut is an entry boundary — the exact reason the as-of
 * sum stays within 64 bits (JournalRepository.sumPostingsAsOf) — and a windowing bug that
 * split an entry would show up as a leg-level residue here.
 *
 * <p>Determinism (ADR-0005): the counterparty pool is created once up front; the account
 * under test is created INSIDE each iteration (cheap, additive-safe, and it keeps every
 * iteration's model self-contained so shrinking replays candidates against fresh accounts
 * rather than a history-dependent model).
 */
@LedgerIntegrationTest
@DisplayName("I10 (end-to-end property): as-of algebra and statement walks reconcile through the real stack")
class AsOfConsistencyPropertyIntegrationTest {

    /** Same rationale as PostingConservationPropertyIntegrationTest: DB round-trips per
     * iteration make ADR-0005's default of 200 hostile; this set is thin by design. */
    private static final String REDUCED_ITERATIONS = "25";

    private static final String CREATED_BY = "asof-property";
    private static final CurrencyCode EUR = new CurrencyCode("EUR");
    private static final long LEG_BOUND = 1_000_000_000L;

    @Autowired
    private CreateAccountUseCase createAccount;

    @Autowired
    private PostJournalEntryUseCase postEntry; // resolves to the metered decorator (@Primary)

    @Autowired
    private GetBalanceQuery getBalance;

    @Autowired
    private GetStatementQuery getStatement;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /** One generated round: the legs of 1..4 entries, MAIN's involvement varying per entry. */
    private record Plan(List<List<Long>> mainAmountsPerEntry, int pageSize) {
    }

    @Test
    @DisplayName("I10: asOf differences equal window sums; asOf(now) = current; walks visit postings exactly once")
    void as_of_algebra_and_walks_reconcile() {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
                CREATED_BY, "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_LEDGER_ADMIN"),
                        new SimpleGrantedAuthority("ROLE_LEDGER_WRITE"),
                        new SimpleGrantedAuthority("ROLE_LEDGER_READ"))));
        AccountId counterparty = createAccount.create(new CreateAccountCommand(
                "asof-prop-counterparty-" + UUID.randomUUID(), EUR, AccountType.EQUITY, true))
                .id();

        Iterations.withReducedDefault(REDUCED_ITERATIONS, () -> Property.check(plans(), plan -> {
            AccountId main = createAccount.create(new CreateAccountCommand(
                    "asof-prop-main-" + UUID.randomUUID(), EUR, AccountType.ASSET, true)).id();

            // Post the plan; record MAIN's model lines (amount per leg) with their REAL
            // posted_at instants as returned by the engine.
            List<Instant> boundaries = new ArrayList<>();
            List<Long> modelAmounts = new ArrayList<>();
            List<Instant> modelInstants = new ArrayList<>();
            for (List<Long> mainLegs : plan.mainAmountsPerEntry()) {
                // M4: fresh UUID key per posting — like the account markers, run-unique on
                // purpose (an rng-derived key would REPLAY on seed replay instead of posting).
                JournalEntry entry = ((PostingOutcome.Posted) postEntry.postEntry(
                        new PostEntryCommand("prop-asof", legsFor(mainLegs, main, counterparty),
                                CREATED_BY, UUID.randomUUID().toString()))).entry();
                boundaries.add(entry.postedAt());
                for (Long amount : mainLegs) {
                    modelAmounts.add(amount);
                    modelInstants.add(entry.postedAt());
                }
            }

            // I10, first form: asOf at every entry boundary equals the model prefix sum.
            for (Instant cut : boundaries) {
                long prefixSum = 0;
                long prefixCount = 0;
                for (int i = 0; i < modelInstants.size(); i++) {
                    if (!modelInstants.get(i).isAfter(cut)) {
                        prefixSum += modelAmounts.get(i);
                        prefixCount++;
                    }
                }
                BalanceView view = getBalance.asOf(main, cut);
                assertThat(view.raw()).as("asOf(%s) raw", cut).isEqualTo(prefixSum);
                assertThat(view.postingCount()).as("asOf(%s) count", cut).isEqualTo(prefixCount);
                assertThat(view.asOf()).contains(cut);
            }

            // I10, second form: asOf(t2) − asOf(t1) = Σ postings in (t1, t2] — checked over
            // every adjacent boundary pair (the strictest windows that exist).
            for (int b = 1; b < boundaries.size(); b++) {
                Instant t1 = boundaries.get(b - 1);
                Instant t2 = boundaries.get(b);
                long windowSum = 0;
                for (int i = 0; i < modelInstants.size(); i++) {
                    if (modelInstants.get(i).isAfter(t1) && !modelInstants.get(i).isAfter(t2)) {
                        windowSum += modelAmounts.get(i);
                    }
                }
                assertThat(getBalance.asOf(main, t2).raw() - getBalance.asOf(main, t1).raw())
                        .as("asOf(t2) − asOf(t1) over (%s, %s]", t1, t2)
                        .isEqualTo(windowSum);
            }

            // I10, third form (quiesced): asOf(now) = current, in both figures.
            BalanceView current = getBalance.current(main);
            if (!boundaries.isEmpty()) {
                BalanceView atLast = getBalance.asOf(main, boundaries.get(boundaries.size() - 1));
                assertThat(atLast.raw()).isEqualTo(current.raw());
                assertThat(atLast.postingCount()).isEqualTo(current.postingCount());
            }

            // The statement walk at the generated page size: exactly the model lines, in
            // order, no duplicates — and it reconciles against the current balance view
            // (postingCount is EXPOSED for precisely this client-side verification).
            List<Posting> walked = walk(main, plan.pageSize());
            assertThat(walked).hasSize(modelAmounts.size());
            assertThat(walked).extracting(posting -> posting.amount().amount())
                    .containsExactlyElementsOf(modelAmounts);
            assertThat(walked).extracting(Posting::postedAt)
                    .containsExactlyElementsOf(modelInstants);
            Set<UUID> distinct = new LinkedHashSet<>();
            walked.forEach(posting -> distinct.add(posting.id().value()));
            assertThat(distinct).hasSize(walked.size());
            assertThat(walked.stream().mapToLong(p -> p.amount().amount()).sum())
                    .isEqualTo(current.raw());
            assertThat(walked.size()).isEqualTo(current.postingCount());
        }));
    }

    /** Pages until the first empty page; bounded so a paging bug fails instead of hanging. */
    private List<Posting> walk(AccountId account, int pageSize) {
        List<Posting> lines = new ArrayList<>();
        StatementSpec spec = StatementSpec.firstPage(pageSize);
        for (int page = 0; page < 200; page++) {
            StatementPage result = getStatement.statement(account,
                    StatementFilter.unbounded(), spec);
            if (result.lines().isEmpty()) {
                return lines;
            }
            lines.addAll(result.lines());
            spec = new StatementSpec(result.next(), pageSize);
        }
        throw new AssertionError("statement walk did not terminate within 200 pages");
    }

    /**
     * MAIN's legs for one entry, balanced against the counterparty. Three entry shapes:
     * a plain two-leg (one MAIN leg), a same-account {+X, −X} pair PLUS a normal leg (MAIN
     * appears three times, entry still balanced), and a counterparty-only entry (MAIN absent —
     * the walk must not see it).
     */
    private static List<EntryDraft.Leg> legsFor(List<Long> mainAmounts, AccountId main,
            AccountId counterparty) {
        List<EntryDraft.Leg> legs = new ArrayList<>();
        long mainTotal = 0;
        for (Long amount : mainAmounts) {
            legs.add(new EntryDraft.Leg(main, Money.of(amount, EUR)));
            mainTotal += amount;
        }
        if (mainTotal != 0) {
            legs.add(new EntryDraft.Leg(counterparty, Money.of(-mainTotal, EUR)));
        }
        if (legs.size() < 2) {
            // Counterparty-only or single-leg shapes still need a balanced ≥2-leg entry.
            legs.add(new EntryDraft.Leg(counterparty, Money.of(7, EUR)));
            legs.add(new EntryDraft.Leg(counterparty, Money.of(-7, EUR)));
        }
        return List.copyOf(legs);
    }

    private static Gen<Plan> plans() {
        Gen<Long> amounts = Gen.longs(-LEG_BOUND, LEG_BOUND).map(a -> a == 0 ? 1L : a);
        Gen<List<Long>> mainLegsPerEntry = Gen.frequency(
                // plain: one MAIN leg
                new Gen.Weighted<>(5, amounts.map(List::of)),
                // the same-account {+X, −X} pair riding with a normal leg (review-mandated)
                new Gen.Weighted<>(2, amounts.flatMap(x -> amounts.map(
                        y -> List.of(Math.abs(x) == 0 ? 1 : Math.abs(x),
                                -(Math.abs(x) == 0 ? 1 : Math.abs(x)), y)))),
                // MAIN sits this entry out entirely
                new Gen.Weighted<>(1, Gen.constant(List.<Long>of())));
        return mainLegsPerEntry.listOf(1, 4).flatMap(entries ->
                Gen.ints(1, 7).map(pageSize -> new Plan(entries, pageSize)));
    }

}
