package io.github.essandhu.ledger.support.property;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.github.essandhu.ledger.domain.model.Account;
import io.github.essandhu.ledger.domain.model.AccountId;
import io.github.essandhu.ledger.domain.model.AccountType;
import io.github.essandhu.ledger.domain.model.CurrencyCode;
import io.github.essandhu.ledger.domain.model.EntryDraft;
import io.github.essandhu.ledger.domain.model.Money;

/**
 * Canonical domain generators for the property suites (ADR-0005: "generators live next to the
 * domain and evolve with it"). Two deliberate biases, both load-bearing:
 *
 * <p><b>Currency exponents.</b> {@link #currencies()} always spans JPY (exponent 0) and BHD
 * (exponent 3) alongside EUR/USD (exponent 2), so any hidden "two decimal places" assumption
 * dies in generation (TEST-STRATEGY §2.2). Minor-unit amounts are exponent-blind by design
 * (ADR-0001) — these generators keep them honest about it.
 *
 * <p><b>Near-limit amounts.</b> {@link #amounts()} reserves probability mass for bands hugging
 * {@code Long.MAX_VALUE}/{@code Long.MIN_VALUE}, because ADR-0001's proof list demands overflow
 * throw {@link ArithmeticException} rather than wrap — a region uniform sampling over 2^64
 * values would visit approximately never.
 *
 * <p>Balanced drafts are constructed per currency as k−1 random legs plus a closing leg
 * (PLAN §1: a multi-currency entry is legal iff EACH currency nets to zero), with leg amounts
 * bounded so the closing sum cannot overflow — the generator must be incapable of manufacturing
 * an {@code AmountOverflow} of its own, or the I1 acceptance property would flake. Unbalanced
 * drafts are balanced drafts plus one nonzero perturbation, delta ±1 included prominently: I1
 * is exact integer equality, so one minor unit off must always reject.
 *
 * <p>Everything here honors {@link Gen}'s determinism contract: values derive only from the
 * supplied rng (account names and ids included — no {@code UUID.randomUUID()}), and account
 * timestamps use a fixed instant, so {@code -Dledger.property.seed} replays generation exactly.
 */
public final class Gens {

    private static final List<CurrencyCode> CURRENCIES = List.of(
            new CurrencyCode("EUR"),
            new CurrencyCode("USD"),
            new CurrencyCode("JPY"),   // exponent 0
            new CurrencyCode("BHD"));  // exponent 3

    /** Fixed open-instant for generated accounts (the domain never reads a clock; neither do generators). */
    private static final Instant OPENED_AT = Instant.parse("2026-07-22T10:00:00Z");

    /** Bound on draft leg amounts: 6 legs × 1e9 keeps every running and closing sum far from
     * {@code Long.MAX_VALUE}, so balanced-draft construction is overflow-safe by arithmetic, not
     * by luck. */
    private static final long LEG_BOUND = 1_000_000_000L;

    /** Width of the near-{@code Long.MAX_VALUE}/{@code MIN_VALUE} amount bands. */
    private static final long LIMIT_BAND = 1_024L;

    private Gens() {
    }

    /** EUR, USD, JPY (exponent 0), BHD (exponent 3) — uniformly, so every run crosses exponents. */
    public static Gen<CurrencyCode> currencies() {
        return rng -> CURRENCIES.get(rng.nextInt(CURRENCIES.size()));
    }

    /**
     * Minor-unit amounts across the interesting regions: everyday magnitudes most of the time,
     * small values (zero included — {@link Money} itself permits zero; only entry legs forbid
     * it), and the near-limit bands where ADR-0001's overflow guarantee lives.
     */
    public static Gen<Long> amounts() {
        return Gen.frequency(
                new Gen.Weighted<>(6, Gen.longs(-1_000_000_000_000L, 1_000_000_000_000L)),
                new Gen.Weighted<>(2, Gen.longs(-1_000L, 1_000L)),
                new Gen.Weighted<>(1, Gen.longs(Long.MAX_VALUE - LIMIT_BAND, Long.MAX_VALUE)),
                new Gen.Weighted<>(1, Gen.longs(Long.MIN_VALUE, Long.MIN_VALUE + LIMIT_BAND)));
    }

    /** {@link #amounts()} tagged with {@link #currencies()}. */
    public static Gen<Money> money() {
        return currencies().flatMap(currency -> amounts().map(amount -> Money.of(amount, currency)));
    }

    /** Account identities derived from the rng (never {@code UUID.randomUUID()} — replayability). */
    public static Gen<AccountId> accountIds() {
        return rng -> new AccountId(new UUID(rng.nextLong(), rng.nextLong()));
    }

    /**
     * Freshly opened ACTIVE accounts mixing all five types and both {@code allowNegative}
     * polarities — the raw material for the M2 end-to-end I4/I5 properties and M5's stateful
     * model runner (ADR-0005).
     */
    public static Gen<Account> accounts() {
        return rng -> Account.open(
                new AccountId(new UUID(rng.nextLong(), rng.nextLong())),
                "prop-account-" + Long.toHexString(rng.nextLong()),
                CURRENCIES.get(rng.nextInt(CURRENCIES.size())),
                AccountType.values()[rng.nextInt(AccountType.values().length)],
                rng.nextBoolean(),
                OPENED_AT);
    }

    /** Small mixed account graphs (2–6 accounts) — see {@link #accounts()}. */
    public static Gen<List<Account>> accountGraphs() {
        return accounts().listOf(2, 6);
    }

    /** Nullable-by-design entry descriptions: mostly absent, otherwise a valid marker string. */
    public static Gen<String> descriptions() {
        return Gen.frequency(
                new Gen.Weighted<>(3, Gen.constant((String) null)),
                new Gen.Weighted<>(1,
                        Gen.longs(Long.MIN_VALUE, Long.MAX_VALUE).map(bits -> "prop-" + Long.toHexString(bits))));
    }

    /**
     * Leg lists that balance to exactly zero per currency: 1–2 currencies, each contributing 2–6
     * legs built as k−1 random nonzero amounts plus the closing leg {@code −sum}. If the k−1
     * legs happen to sum to zero, the first is nudged off the axis so the closing leg stays
     * nonzero (I2 — the generator must never manufacture its own zero leg). Each currency draws
     * accounts from its own small pool, so same-account multi-leg entries occur and the drafts
     * stay plausible for the end-to-end suites (an account holds exactly one currency,
     * PLAN §4.1).
     *
     * <p>Returned as raw ingredients rather than an {@link EntryDraft}, because for the I1/I2
     * properties construction itself is the assertion under test.
     */
    public static Gen<List<EntryDraft.Leg>> balancedLegs() {
        return rng -> {
            int currencyCount = rng.nextInt(1, 3); // 1..2 currencies
            int firstCurrency = rng.nextInt(CURRENCIES.size());
            List<CurrencyCode> chosen = new ArrayList<>();
            chosen.add(CURRENCIES.get(firstCurrency));
            if (currencyCount == 2) {
                int offset = rng.nextInt(1, CURRENCIES.size()); // 1..3 → always a distinct second currency
                chosen.add(CURRENCIES.get((firstCurrency + offset) % CURRENCIES.size()));
            }
            List<EntryDraft.Leg> legs = new ArrayList<>();
            for (CurrencyCode currency : chosen) {
                List<AccountId> pool = new ArrayList<>();
                int poolSize = rng.nextInt(2, 6); // 2..5 accounts per currency
                for (int i = 0; i < poolSize; i++) {
                    pool.add(new AccountId(new UUID(rng.nextLong(), rng.nextLong())));
                }
                int legCount = rng.nextInt(2, 7); // 2..6 legs per currency
                long[] amounts = new long[legCount - 1];
                long sum = 0;
                for (int i = 0; i < legCount - 1; i++) {
                    amounts[i] = nonZero(rng.nextLong(-LEG_BOUND, LEG_BOUND + 1));
                    // Bounded well below Long.MAX_VALUE (LEG_BOUND comment) — addExact is belt
                    // and braces, matching the domain's checked-arithmetic habit (ADR-0001).
                    sum = Math.addExact(sum, amounts[i]);
                }
                if (sum == 0) {
                    long nudged = amounts[0] == -1 ? 1 : amounts[0] + 1;
                    sum = Math.addExact(sum - amounts[0], nudged);
                    amounts[0] = nudged;
                }
                for (int i = 0; i < legCount - 1; i++) {
                    legs.add(new EntryDraft.Leg(
                            pool.get(rng.nextInt(pool.size())), Money.of(amounts[i], currency)));
                }
                legs.add(new EntryDraft.Leg(
                        pool.get(rng.nextInt(pool.size())), Money.of(Math.negateExact(sum), currency)));
            }
            return List.copyOf(legs);
        };
    }

    /** Fully constructed balanced drafts — for suites (end-to-end I4/I5, M5's model runner)
     * that consume drafts rather than test their construction. */
    public static Gen<EntryDraft> balancedDrafts() {
        return descriptions().flatMap(description ->
                balancedLegs().map(legs -> new EntryDraft(description, legs)));
    }

    /**
     * A balanced draft with exactly one leg perturbed by a nonzero delta — everything about it
     * is valid EXCEPT balance, so I1 ({@code UnbalancedEntry}) is the only rule it can trip and
     * the property asserting rejection is sharp. The perturbed leg is kept nonzero (doubling the
     * delta when the perturbation would land on zero — the leg was {@code −delta}, so
     * {@code +2·delta} lands on {@code +delta}), and deltas of exactly ±1 are drawn often: one
     * minor unit off MUST reject, or "exact integer equality" means nothing.
     */
    public static Gen<Unbalanced> unbalancedDrafts() {
        return descriptions().flatMap(description ->
                balancedLegs().flatMap(balanced ->
                        deltas().flatMap(rawDelta ->
                                Gen.ints(0, balanced.size() - 1).map(index -> {
                                    EntryDraft.Leg target = balanced.get(index);
                                    long original = target.amount().amount();
                                    long delta = rawDelta;
                                    if (original + delta == 0) {
                                        delta = Math.multiplyExact(delta, 2);
                                    }
                                    long perturbed = Math.addExact(original, delta);
                                    List<EntryDraft.Leg> legs = new ArrayList<>(balanced);
                                    legs.set(index, new EntryDraft.Leg(
                                            target.accountId(),
                                            Money.of(perturbed, target.amount().currency())));
                                    return new Unbalanced(
                                            description, List.copyOf(legs),
                                            target.amount().currency(), delta);
                                }))));
    }

    /**
     * Ingredients of a draft that fails I1 in exactly one currency: the leg list, the currency
     * whose sum was broken, and the delta it is off by — which is precisely the residual an
     * {@code UnbalancedEntry} must report for it.
     */
    public record Unbalanced(
            String description, List<EntryDraft.Leg> legs, CurrencyCode currency, long delta) {
    }

    private static Gen<Long> deltas() {
        return Gen.frequency(
                new Gen.Weighted<>(1, Gen.constant(1L)),
                new Gen.Weighted<>(1, Gen.constant(-1L)),
                new Gen.Weighted<>(2, Gen.longs(-1_000L, 1_000L).map(Gens::nonZero)));
    }

    private static long nonZero(long draw) {
        return draw == 0 ? 1 : draw;
    }
}
