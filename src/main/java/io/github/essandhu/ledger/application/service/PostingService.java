package io.github.essandhu.ledger.application.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

import io.github.essandhu.ledger.application.port.in.EntryNotFound;
import io.github.essandhu.ledger.application.port.in.GetJournalEntryQuery;
import io.github.essandhu.ledger.application.port.in.PostJournalEntryUseCase;
import io.github.essandhu.ledger.application.port.in.ReverseEntryUseCase;
import io.github.essandhu.ledger.application.port.in.TransferFundsUseCase;
import io.github.essandhu.ledger.application.port.out.AccountRepository;
import io.github.essandhu.ledger.application.port.out.BalanceRepository;
import io.github.essandhu.ledger.application.port.out.IdGenerator;
import io.github.essandhu.ledger.application.port.out.JournalRepository;
import io.github.essandhu.ledger.domain.error.AmountOverflow;
import io.github.essandhu.ledger.domain.error.CurrencyMismatch;
import io.github.essandhu.ledger.domain.error.EntryAlreadyReversed;
import io.github.essandhu.ledger.domain.error.OverdraftViolation;
import io.github.essandhu.ledger.domain.error.UnknownPostingAccount;
import io.github.essandhu.ledger.domain.model.Account;
import io.github.essandhu.ledger.domain.model.AccountBalance;
import io.github.essandhu.ledger.domain.model.AccountId;
import io.github.essandhu.ledger.domain.model.EntryDraft;
import io.github.essandhu.ledger.domain.model.EntryId;
import io.github.essandhu.ledger.domain.model.EntryType;
import io.github.essandhu.ledger.domain.model.JournalEntry;
import io.github.essandhu.ledger.domain.model.PostingId;

/**
 * The posting engine (PLAN §6): one class, four ports, ONE critical section. Every
 * money-moving flow — journal, transfer, reversal — funnels through {@link #postUnderLock},
 * which implements ADR-0003 §Decision verbatim: validate the draft with no I/O, lock the
 * balance snapshots of every touched account in canonical order, decide everything
 * account-dependent under those locks, then write. Registered by config wiring, not component
 * scanning; {@code @Transactional} and {@code @PreAuthorize} (I13's method-security layer,
 * LEDGER_WRITE on the money movers per PLAN §5) are its only Spring surface (I14).
 *
 * <p>Because the single lock site orders ids canonically and nothing else in the application
 * takes these locks out of order, no interleaving can deadlock — rejected requests fail with a
 * domain error, never a lock or serialization error, and there is no server-side retry loop
 * (ADR-0003). Because validation precedes every write and the whole method is one transaction,
 * a rejected posting writes NOTHING (ADR-0004: retries re-execute against clean state).
 */
public class PostingService implements PostJournalEntryUseCase, TransferFundsUseCase,
        ReverseEntryUseCase, GetJournalEntryQuery {

    private final AccountRepository accounts;
    private final JournalRepository journal;
    private final BalanceRepository balances;
    private final IdGenerator ids;
    private final Clock clock;

    public PostingService(AccountRepository accounts, JournalRepository journal,
            BalanceRepository balances, IdGenerator ids, Clock clock) {
        this.accounts = accounts;
        this.journal = journal;
        this.balances = balances;
        this.ids = ids;
        this.clock = clock;
    }

    @Override
    @PreAuthorize("hasRole('LEDGER_WRITE')")
    @Transactional
    public JournalEntry postEntry(PostEntryCommand command) {
        // Step 1 (ADR-0003): pure draft validation — I1/I2 fail here, before any I/O.
        EntryDraft draft = new EntryDraft(command.description(), command.legs());
        return postUnderLock(EntryType.JOURNAL, draft, null, command.createdBy());
    }

    @Override
    @PreAuthorize("hasRole('LEDGER_WRITE')")
    @Transactional
    public JournalEntry transfer(TransferCommand command) {
        // PLAN §5, literally: source = DEBIT = +amount, target = CREDIT = −amount. One Money,
        // so the pair balances by construction. negateExact has no answer for Long.MIN_VALUE
        // — translated here, at the accumulation point, per the Money javadoc's contract.
        EntryDraft.Leg sourceLeg = new EntryDraft.Leg(command.source(), command.amount());
        EntryDraft.Leg targetLeg;
        try {
            targetLeg = new EntryDraft.Leg(command.target(), command.amount().negate());
        } catch (ArithmeticException overflow) {
            throw new AmountOverflow(
                    "transfer amount has no 64-bit negation (ADR-0001: checked arithmetic rejects, never wraps)");
        }
        EntryDraft draft = new EntryDraft(command.description(), List.of(sourceLeg, targetLeg));
        return postUnderLock(EntryType.TRANSFER, draft, null, command.createdBy());
    }

    @Override
    @PreAuthorize("hasRole('LEDGER_WRITE')")
    @Transactional
    public JournalEntry reverse(ReverseCommand command) {
        // The original's id is a PATH id, so a miss is the 404 EntryNotFound (PLAN §5) — the
        // one pre-lock read of this flow, and deliberately not a status read: immutable rows
        // cannot go stale (I3).
        JournalEntry original = journal.findById(command.originalId())
                .orElseThrow(() -> new EntryNotFound(command.originalId()));
        EntryDraft draft;
        try {
            draft = JournalEntry.reversalDraftOf(original, command.description());
        } catch (ArithmeticException overflow) {
            throw new AmountOverflow(
                    "reversal of entry %s has a leg with no 64-bit negation (ADR-0001: checked arithmetic rejects, never wraps)"
                            .formatted(original.id().value()));
        }
        // From here the reversal is an ordinary posting (I11: same validation, same locks,
        // same status checks — reversing into a FROZEN account fails, the documented caveat).
        return postUnderLock(EntryType.REVERSAL, draft, original.id(), command.createdBy());
    }

    @Override
    @PreAuthorize("hasRole('LEDGER_READ')")
    @Transactional(readOnly = true)
    public JournalEntry byId(EntryId id) {
        return journal.findById(id).orElseThrow(() -> new EntryNotFound(id));
    }

    /**
     * ADR-0003 §Decision, steps 2–6, in order and nothing in between. The draft (step 1) is
     * already proven; everything account-dependent is decided strictly under the locks, and
     * the first write happens only after the last check — so rejection at any step leaves the
     * database untouched (ADR-0004).
     */
    private JournalEntry postUnderLock(EntryType entryType, EntryDraft draft, EntryId reversalOf,
            String createdBy) {
        // Step 2: distinct touched accounts in canonical bytewise UUID order — the one order
        // every lock taker in the system agrees on (BalanceRepository.CANONICAL_ORDER).
        List<AccountId> touched = draft.legs().stream()
                .map(EntryDraft.Leg::accountId)
                .distinct()
                .sorted(BalanceRepository.CANONICAL_ORDER)
                .toList();
        List<AccountBalance> locked = balances.lockBalances(touched);

        // Step 3: a missing snapshot row IS a missing account (every real account has one by
        // construction: V3 backfill + create-account-tx insert). Reported as the 422
        // UnknownPostingAccount with EVERY unknown id — never folded into the 404.
        Map<AccountId, AccountBalance> lockedById = new LinkedHashMap<>();
        for (AccountBalance balance : locked) {
            lockedById.put(balance.accountId(), balance);
        }
        Set<AccountId> unknown = new LinkedHashSet<>();
        for (AccountId id : touched) {
            if (!lockedById.containsKey(id)) {
                unknown.add(id);
            }
        }
        if (!unknown.isEmpty()) {
            throw new UnknownPostingAccount(unknown);
        }

        // I11, inside the locked section: the shared account-set lock serializes concurrent
        // double-reversal attempts, so at most one sees "no reversal yet"; the partial unique
        // index journal_entry_reversed_once is the at-rest backstop.
        if (reversalOf != null && journal.reversalExistsFor(reversalOf)) {
            throw new EntryAlreadyReversed(reversalOf);
        }

        // Step 4: the ONLY status read, after the locks — no pre-lock resolve exists, so
        // there is no stale-status window for a concurrent freeze or close to slip through.
        Map<AccountId, Account> accountsById = new LinkedHashMap<>();
        for (Account account : accounts.findByIds(touched)) {
            accountsById.put(account.id(), account);
        }
        if (accountsById.size() != touched.size()) {
            // Unreachable by construction: account_balance carries a foreign key to account,
            // so a locked snapshot row without its account row is corruption, not a client
            // error — fail loudly rather than NPE three lines later.
            throw new IllegalStateException(
                    "account rows missing under lock (account_balance FK violated?) for " + touched);
        }
        for (EntryDraft.Leg leg : draft.legs()) {
            Account account = accountsById.get(leg.accountId());
            if (!account.currency().equals(leg.amount().currency())) {
                throw new CurrencyMismatch(account.currency(), leg.amount().currency());
            }
            account.ensureAcceptsPostings();
        }

        // Step 5 (I6): per-account net deltas with checked arithmetic. The delta is summed
        // separately from the new balance because BOTH must be representable — the new
        // balance for the overdraft judgment, the delta because ADR-0002's single UPDATE
        // applies it as one number. Overflow at either point is the 422 AmountOverflow,
        // never a wrapped long (ADR-0001).
        Map<AccountId, Long> deltas = new LinkedHashMap<>();
        Map<AccountId, Long> legCounts = new LinkedHashMap<>();
        for (EntryDraft.Leg leg : draft.legs()) {
            try {
                deltas.merge(leg.accountId(), leg.amount().amount(), Math::addExact);
            } catch (ArithmeticException overflow) {
                throw new AmountOverflow(
                        "entry legs on account %s sum outside 64-bit minor units (ADR-0001: checked arithmetic rejects, never wraps)"
                                .formatted(leg.accountId().value()));
            }
            legCounts.merge(leg.accountId(), 1L, Long::sum);
        }
        for (AccountBalance balance : locked) { // canonical order ⇒ deterministic first offender
            Account account = accountsById.get(balance.accountId());
            long newRaw;
            try {
                newRaw = Math.addExact(balance.balance(), deltas.get(balance.accountId()));
            } catch (ArithmeticException overflow) {
                throw new AmountOverflow(
                        "account %s balance would leave 64-bit minor units (ADR-0001: checked arithmetic rejects, never wraps)"
                                .formatted(balance.accountId().value()));
            }
            if (!account.allowNegative()) {
                // Natural = raw × direction, CHECKED: at newRaw = Long.MIN_VALUE with
                // direction −1 the product has no 64-bit representation, and a wrapping *
                // would misfile the astronomically positive position as an overdraft (see
                // AccountBalance.natural — the same lone edge negateExact refuses).
                long newNatural;
                try {
                    newNatural = Math.multiplyExact(newRaw, account.type().direction());
                } catch (ArithmeticException overflow) {
                    throw new AmountOverflow(
                            "account %s natural balance has no 64-bit representation (ADR-0001: checked arithmetic rejects, never wraps)"
                                    .formatted(account.id().value()));
                }
                if (newNatural < 0) {
                    throw new OverdraftViolation(account.id(), newNatural);
                }
            }
        }

        // Step 6: posted_at read from the injected Clock UNDER the lock (PLAN §4.6 — exact
        // per-account as-of ordering), ids from the generator port, then the writes: entry
        // with postings, and ADR-0002's snapshot bump per touched account, same transaction.
        Instant postedAt = clock.instant();
        EntryId entryId = new EntryId(ids.nextId());
        List<PostingId> postingIds = new ArrayList<>(draft.legs().size());
        for (int i = 0; i < draft.legs().size(); i++) {
            postingIds.add(new PostingId(ids.nextId()));
        }
        JournalEntry entry = JournalEntry.post(entryId, entryType, draft, reversalOf, createdBy,
                postedAt, postingIds);
        journal.insert(entry);
        for (AccountBalance balance : locked) {
            balances.applyDelta(balance.accountId(), deltas.get(balance.accountId()),
                    legCounts.get(balance.accountId()), postedAt);
        }
        return entry;
    }
}
