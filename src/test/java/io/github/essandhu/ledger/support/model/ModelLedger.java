package io.github.essandhu.ledger.support.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import io.github.essandhu.ledger.application.port.out.BalanceRepository;
import io.github.essandhu.ledger.domain.error.AccountBalanceNotZero;
import io.github.essandhu.ledger.domain.error.AccountClosed;
import io.github.essandhu.ledger.domain.error.AccountFrozen;
import io.github.essandhu.ledger.domain.error.CurrencyMismatch;
import io.github.essandhu.ledger.domain.error.EntryAlreadyReversed;
import io.github.essandhu.ledger.domain.error.InvalidStatusTransition;
import io.github.essandhu.ledger.domain.error.OverdraftViolation;
import io.github.essandhu.ledger.domain.model.AccountId;
import io.github.essandhu.ledger.domain.model.AccountStatus;
import io.github.essandhu.ledger.domain.model.AccountType;
import io.github.essandhu.ledger.domain.model.CurrencyCode;
import io.github.essandhu.ledger.domain.model.EntryDraft;
import io.github.essandhu.ledger.domain.model.Money;

/**
 * The sequential in-memory model ledger of the M5 stateful model-vs-SUT suite (ADR-0005's
 * command-sequence runner, ADR-0003 §Proof). A few maps and longs that re-decide, in plain
 * Java, exactly what the real service decides through Spring, JPA, and PostgreSQL — same
 * verdicts, same DECISION ORDER, same arithmetic. The property suite applies identical command
 * sequences to both and compares verdict plus full observable state after every step; any
 * divergence — a check reordered, a rule inverted, a balance mis-bumped — fails with the
 * command sequence as the counterexample.
 *
 * <p>Decision order is contract, mirrored from the SUT (each rule cites its source):
 * <ol>
 * <li>Reversal target resolution — an unknown entry is {@code EntryNotFound} before anything
 *     else ({@code PostingService.reverse}: the one pre-lock read). The suite maps that case
 *     itself; the model's {@link #reverse} takes a resolved index.</li>
 * <li>Already-reversed, before any per-leg judgment ({@code postUnderLock}, the I11 check
 *     inside the locked section).</li>
 * <li>Per LEG, in draft order: currency-vs-account match, then lifecycle status — FROZEN
 *     rejects before CLOSED is even reachable on a later leg ({@code postUnderLock} step 4's
 *     loop: mismatch throws before {@code ensureAcceptsPostings} of the SAME leg, and leg
 *     {@code n}'s verdict precedes leg {@code n+1}'s entirely).</li>
 * <li>Overdraft, scanned in CANONICAL account order — which pins the deterministic FIRST
 *     OFFENDER the suite also compares ({@code postUnderLock} step 5: "canonical order ⇒
 *     deterministic first offender").</li>
 * </ol>
 *
 * <p>Deliberately NOT modeled, with reasons: idempotency (the suite spends a fresh key per
 * command — replay/conflict semantics have their own proofs, I8/I9); draft-shape rules
 * (I1/I2 — the generator emits only balanced nonzero two-leg drafts, so a draft rejection
 * would surface as a verdict mismatch and fail the suite loudly); 64-bit overflow (amounts
 * are bounded far below the band, ADR-0001's property suite owns that region); and time
 * (posted_at is commit-order bookkeeping, invisible to balance/status equivalence).
 */
public final class ModelLedger {

    /** A modeled account: the mutable observable state the suite compares per step. */
    public static final class ModelAccount {
        private final AccountId id;
        private final CurrencyCode currency;
        private final AccountType type;
        private final boolean allowNegative;
        private AccountStatus status = AccountStatus.ACTIVE;
        private long balance;
        private long postingCount;

        private ModelAccount(AccountId id, CurrencyCode currency, AccountType type,
                boolean allowNegative) {
            this.id = id;
            this.currency = currency;
            this.type = type;
            this.allowNegative = allowNegative;
        }

        public CurrencyCode currency() {
            return currency;
        }

        public AccountStatus status() {
            return status;
        }

        public long balance() {
            return balance;
        }

        public long postingCount() {
            return postingCount;
        }
    }

    /** One accepted entry's legs; whether a reversal already negated entry {@code i} is
     * tracked positionally in the {@link #reversed} index set, not on the record. */
    private record PostedEntry(List<EntryDraft.Leg> legs) {
    }

    /**
     * A verdict: {@code accepted}, or the rejection's class — plus, for the overdraft, WHICH
     * account offended (the canonical-order first offender, pinned behavior worth comparing).
     */
    public record Verdict(Class<? extends RuntimeException> rejection, AccountId offender) {
        public static final Verdict ACCEPTED = new Verdict(null, null);

        public static Verdict rejected(Class<? extends RuntimeException> rejection) {
            return new Verdict(rejection, null);
        }

        public boolean accepted() {
            return rejection == null;
        }
    }

    private final Map<AccountId, ModelAccount> accounts = new LinkedHashMap<>();
    private final List<PostedEntry> entries = new ArrayList<>();
    private final Set<Integer> reversed = new LinkedHashSet<>();

    public void open(AccountId id, CurrencyCode currency, AccountType type,
            boolean allowNegative) {
        Objects.requireNonNull(id, "id");
        accounts.put(id, new ModelAccount(id, currency, type, allowNegative));
    }

    public ModelAccount account(AccountId id) {
        return Objects.requireNonNull(accounts.get(id), "unknown model account " + id);
    }

    /** Number of accepted entries so far — reversals included, exactly like journal_entry. */
    public int postedEntryCount() {
        return entries.size();
    }

    /** A balanced draft's legs, as the journal/transfer surface submits them. */
    public Verdict post(List<EntryDraft.Leg> legs) {
        return decideAndApply(legs, null);
    }

    /**
     * Reversal of accepted entry {@code index} (the suite resolves ordinals to indexes and
     * handles the unknown-entry 404 itself). The reversing entry, when accepted, is a fresh
     * entry in its own right — reversals of reversals are legal, exactly as in the SUT
     * (the at-most-once rule binds each ORIGINAL, {@code reversal_of} is unique per target).
     */
    public Verdict reverse(int index) {
        List<EntryDraft.Leg> negated = entries.get(index).legs().stream()
                .map(leg -> new EntryDraft.Leg(leg.accountId(), leg.amount().negate()))
                .toList();
        return decideAndApply(negated, index);
    }

    /** Mirrors {@code AccountService.applyStatus}: same-state no-op → edge legality → the
     * CLOSED-only zero-balance precondition, in that order. */
    public Verdict updateStatus(AccountId id, AccountStatus target) {
        ModelAccount account = account(id);
        if (account.status == target) {
            return Verdict.ACCEPTED; // declarative no-op, nothing persisted
        }
        if (account.status == AccountStatus.CLOSED) {
            return Verdict.rejected(InvalidStatusTransition.class);
        }
        if (target == AccountStatus.CLOSED && account.type.natural(account.balance) != 0) {
            return Verdict.rejected(AccountBalanceNotZero.class);
        }
        account.status = target;
        return Verdict.ACCEPTED;
    }

    private Verdict decideAndApply(List<EntryDraft.Leg> legs, Integer reversalOf) {
        // I11 before any per-leg judgment (postUnderLock's in-lock ordering).
        if (reversalOf != null && reversed.contains(reversalOf)) {
            return Verdict.rejected(EntryAlreadyReversed.class);
        }
        // Per leg, in draft order: currency, then status — the SUT's exact loop.
        for (EntryDraft.Leg leg : legs) {
            ModelAccount account = account(leg.accountId());
            if (!account.currency.equals(leg.amount().currency())) {
                return Verdict.rejected(CurrencyMismatch.class);
            }
            if (account.status == AccountStatus.FROZEN) {
                return Verdict.rejected(AccountFrozen.class);
            }
            if (account.status == AccountStatus.CLOSED) {
                return Verdict.rejected(AccountClosed.class);
            }
        }
        // Per-account net deltas, then the overdraft scan in canonical order — the same order
        // the SUT walks its locked rows, so the FIRST offender matches deterministically.
        Map<AccountId, Long> deltas = new LinkedHashMap<>();
        Map<AccountId, Long> legCounts = new LinkedHashMap<>();
        for (EntryDraft.Leg leg : legs) {
            deltas.merge(leg.accountId(), leg.amount().amount(), Math::addExact);
            legCounts.merge(leg.accountId(), 1L, Long::sum);
        }
        List<AccountId> touched = deltas.keySet().stream()
                .sorted(BalanceRepository.CANONICAL_ORDER)
                .toList();
        for (AccountId id : touched) {
            ModelAccount account = account(id);
            long newRaw = Math.addExact(account.balance, deltas.get(id));
            if (!account.allowNegative && account.type.natural(newRaw) < 0) {
                return new Verdict(OverdraftViolation.class, id);
            }
        }
        // Accepted: apply the deltas and remember the entry (rejections applied NOTHING —
        // the model is all-or-nothing exactly like the one-transaction SUT).
        for (AccountId id : touched) {
            ModelAccount account = account(id);
            account.balance += deltas.get(id);
            account.postingCount += legCounts.get(id);
        }
        entries.add(new PostedEntry(List.copyOf(legs)));
        if (reversalOf != null) {
            reversed.add(reversalOf);
        }
        return Verdict.ACCEPTED;
    }

    /** Test-visible helper mirroring the transfer expansion (source = DEBIT
     * {@code +amount}, target = CREDIT {@code −amount}, one Money each way). */
    public static List<EntryDraft.Leg> transferLegs(AccountId source, AccountId target,
            Money amount) {
        return List.of(new EntryDraft.Leg(source, amount),
                new EntryDraft.Leg(target, amount.negate()));
    }
}
