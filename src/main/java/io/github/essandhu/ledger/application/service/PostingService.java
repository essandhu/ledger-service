package io.github.essandhu.ledger.application.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

import io.github.essandhu.ledger.application.port.in.CanonicalCommand;
import io.github.essandhu.ledger.application.port.in.EntryNotFound;
import io.github.essandhu.ledger.application.port.in.GetJournalEntryQuery;
import io.github.essandhu.ledger.application.port.in.IdempotencyKeyConflict;
import io.github.essandhu.ledger.application.port.in.PostJournalEntryUseCase;
import io.github.essandhu.ledger.application.port.in.PostingOutcome;
import io.github.essandhu.ledger.application.port.in.ReverseEntryUseCase;
import io.github.essandhu.ledger.application.port.in.TransferFundsUseCase;
import io.github.essandhu.ledger.application.port.out.AccountRepository;
import io.github.essandhu.ledger.application.port.out.BalanceRepository;
import io.github.essandhu.ledger.application.port.out.IdGenerator;
import io.github.essandhu.ledger.application.port.out.IdempotencyRecord;
import io.github.essandhu.ledger.application.port.out.IdempotencyRepository;
import io.github.essandhu.ledger.application.port.out.JournalRepository;
import io.github.essandhu.ledger.application.port.out.WriteResponseRenderer;
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
 * The posting engine (PLAN §6): one class, ONE critical section. Every
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
 *
 * <p>M4 (ADR-0004): each write method first computes the SHA-256 of its command's frozen
 * canonical form, then asks {@link #settledOutcome} whether this (principal, key) is already
 * settled — a recorded success with an equal hash is a {@link PostingOutcome.Replayed}
 * short-circuit (nothing executed, nothing locked), a different hash is the
 * {@link IdempotencyKeyConflict} rejection (nothing written), a purged record falls back to
 * the permanent entry (option 3b's degraded replay), and only a genuine miss proceeds to
 * post. After a successful posting, the response is rendered and the record inserted IN THE
 * SAME transaction as the entry — one commit, one truth. Concurrent duplicates settle in two
 * layers: same-payload duplicates serialize on the balance locks and the loser answers replay
 * from the under-lock re-read in {@link #postUnderLock} (never a domain 422 recomputed
 * against post-winner state); duplicates sharing no lock fall to V3's backstop index, whose
 * violation dooms the loser's transaction and is deliberately NOT caught here — the web
 * adapter retries the use case once in a fresh transaction, which resolves as replay/conflict
 * (or, if the winner aborted, as an ordinary first attempt).
 */
public class PostingService implements PostJournalEntryUseCase, TransferFundsUseCase,
        ReverseEntryUseCase, GetJournalEntryQuery {

    private final AccountRepository accounts;
    private final JournalRepository journal;
    private final BalanceRepository balances;
    private final IdempotencyRepository idempotency;
    private final WriteResponseRenderer responses;
    private final IdGenerator ids;
    private final Clock clock;
    private final Duration idempotencyTtl;

    public PostingService(AccountRepository accounts, JournalRepository journal,
            BalanceRepository balances, IdempotencyRepository idempotency,
            WriteResponseRenderer responses, IdGenerator ids, Clock clock,
            Duration idempotencyTtl) {
        this.accounts = accounts;
        this.journal = journal;
        this.balances = balances;
        this.idempotency = idempotency;
        this.responses = responses;
        this.ids = ids;
        this.clock = clock;
        this.idempotencyTtl = idempotencyTtl;
    }

    @Override
    @PreAuthorize("hasRole('LEDGER_WRITE')")
    @Transactional
    public PostingOutcome postEntry(PostEntryCommand command) {
        // ADR-0004: the idempotency verdict precedes even draft validation — a replay
        // re-executes nothing, and a conflict outranks whatever else is wrong (both 422s;
        // the conflict names the client bug).
        String requestHash = CanonicalCommand.hash(command);
        Optional<PostingOutcome> settled =
                settledOutcome(command.createdBy(), command.idempotencyKey(), requestHash);
        if (settled.isPresent()) {
            return settled.get();
        }
        // Step 1 (ADR-0003): pure draft validation — I1/I2 fail here, before any I/O.
        EntryDraft draft = new EntryDraft(command.description(), command.legs());
        return postUnderLock(EntryType.JOURNAL, draft, null, command.createdBy(),
                command.idempotencyKey(), requestHash);
    }

    @Override
    @PreAuthorize("hasRole('LEDGER_WRITE')")
    @Transactional
    public PostingOutcome transfer(TransferCommand command) {
        String requestHash = CanonicalCommand.hash(command);
        Optional<PostingOutcome> settled =
                settledOutcome(command.createdBy(), command.idempotencyKey(), requestHash);
        if (settled.isPresent()) {
            return settled.get();
        }
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
        return postUnderLock(EntryType.TRANSFER, draft, null, command.createdBy(),
                command.idempotencyKey(), requestHash);
    }

    @Override
    @PreAuthorize("hasRole('LEDGER_WRITE')")
    @Transactional
    public PostingOutcome reverse(ReverseCommand command) {
        // Idempotency before the original-entry lookup: a replayed reversal answers from the
        // record without probing anything, so only a first attempt can 404.
        String requestHash = CanonicalCommand.hash(command);
        Optional<PostingOutcome> settled =
                settledOutcome(command.createdBy(), command.idempotencyKey(), requestHash);
        if (settled.isPresent()) {
            return settled.get();
        }
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
        return postUnderLock(EntryType.REVERSAL, draft, original.id(), command.createdBy(),
                command.idempotencyKey(), requestHash);
    }

    /**
     * The full ADR-0004 verdict for (principal, key): empty = unclaimed, proceed to post; a
     * {@link PostingOutcome.Replayed} = this key already succeeded; the
     * {@link IdempotencyKeyConflict} = recorded success with a DIFFERENT hash — key reuse,
     * rejected before any lock or write. Two sources, in order: the idempotency record (the
     * normal case — stored body byte for byte, hash-discriminated), then the PERMANENT entry
     * itself via V3's backstop index (option 3b's designed degradation, live from birth: if
     * the record was purged, the entry still says this key succeeded — the replay body is
     * RECONSTRUCTED, so it may not be byte-identical, and replay-vs-conflict discrimination is
     * gone because the hash went with the record; money stays safe, diagnostics degrade).
     * Without the fallback, a purged key's retry would re-execute into the backstop index and
     * error forever — precisely the wedge the ADR's retention design exists to preclude.
     */
    private Optional<PostingOutcome> settledOutcome(String createdBy, String idempotencyKey,
            String requestHash) {
        Optional<PostingOutcome> recorded = replayOrConflict(createdBy, idempotencyKey,
                requestHash);
        if (recorded.isPresent()) {
            return recorded;
        }
        return journal.findByCreatorAndKey(createdBy, idempotencyKey)
                .map(entry -> new PostingOutcome.Replayed(responses.render(entry).body()));
    }

    /** The record half of {@link #settledOutcome}: replay or conflict from the bookkeeping
     * row alone. Split out because the under-lock re-read needs ONLY this half — a record
     * committed while we waited on the locks cannot have been purged in the same instant. */
    private Optional<PostingOutcome> replayOrConflict(String createdBy, String idempotencyKey,
            String requestHash) {
        return idempotency.find(createdBy, idempotencyKey).map(record -> {
            if (!record.requestHash().equals(requestHash)) {
                throw new IdempotencyKeyConflict(idempotencyKey);
            }
            return new PostingOutcome.Replayed(record.responseBody());
        });
    }

    /**
     * The same-transaction record write (ADR-0004 §Decision, reason 1): rendered response plus
     * hash, committed atomically with the entry it records. {@code createdAt} is the entry's
     * own {@code postedAt} — one transaction, one instant (and the clamped instant is the one
     * the response body carries); {@code expiresAt} = createdAt + TTL, populated from birth so
     * enabling the purge is configuration, not migration.
     */
    private void record(String createdBy, String idempotencyKey, String requestHash,
            JournalEntry entry) {
        WriteResponseRenderer.Rendered rendered = responses.render(entry);
        idempotency.insert(new IdempotencyRecord(createdBy, idempotencyKey, requestHash,
                entry.id(), rendered.status(), rendered.body(), entry.postedAt(),
                entry.postedAt().plus(idempotencyTtl)));
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
    private PostingOutcome postUnderLock(EntryType entryType, EntryDraft draft,
            EntryId reversalOf, String createdBy, String idempotencyKey, String requestHash) {
        // Step 2: distinct touched accounts in canonical bytewise UUID order — the one order
        // every lock taker in the system agrees on (BalanceRepository.CANONICAL_ORDER).
        List<AccountId> touched = draft.legs().stream()
                .map(EntryDraft.Leg::accountId)
                .distinct()
                .sorted(BalanceRepository.CANONICAL_ORDER)
                .toList();
        List<AccountBalance> locked = balances.lockBalances(touched);

        // M4, the same-key race's settled ending (ADR-0004): re-read the idempotency record
        // UNDER the locks. A same-payload duplicate serializes on these very rows, so if a
        // winner committed while we waited, READ COMMITTED makes its record visible to this
        // statement — and the duplicate answers replay (or conflict) HERE, before any
        // account-dependent judgment. Without this re-read, the loser would re-validate
        // against post-winner state and could be misfiled as a domain rejection: a duplicate
        // reversal sees the winner's reversal and answers entry-already-reversed, a duplicate
        // transfer that drained a strict account answers overdraft — 422s for an operation
        // that SUCCEEDED, the exact failure-mode inversion ADR-0004's option-2b analysis
        // warns invites a fresh-key resend. The record half suffices (a record committed
        // moments ago cannot be purged); the backstop-index violation plus the web adapter's
        // fresh-transaction retry remains for duplicates whose payloads share no lock.
        Optional<PostingOutcome> settledWhileWaiting =
                replayOrConflict(createdBy, idempotencyKey, requestHash);
        if (settledWhileWaiting.isPresent()) {
            return settledWhileWaiting.get();
        }

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
                // AccountType.natural is CHECKED: at newRaw = Long.MIN_VALUE with direction
                // −1 the product has no 64-bit representation, and a wrapping * would misfile
                // the astronomically positive position as an overdraft.
                long newNatural;
                try {
                    newNatural = account.type().natural(newRaw);
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

        // Step 6: posted_at read from the injected Clock UNDER the lock, then clamped
        // strictly above every touched account's last posted_at (PLAN §4.6): per-account
        // posted_at order equals commit order BY CONSTRUCTION, not by trusting the wall
        // clock. A backwards step (NTP correction, VM resume — the threat MonotoneUuidClock
        // already guards ids against) would otherwise let a later-committed posting sort
        // before an earlier one, permanently skipping it past M3's keyset cursors and letting
        // as-of sums select a never-committed subset. The floor is the LOCKED snapshot row's
        // updated_at — which IS the account's last posted_at whenever posting_count > 0
        // (applyDelta below writes them together) — so the guarantee holds across app
        // instances, not just within one process. posting_count = 0 means updated_at is
        // creation time, not posting history: no floor, a first posting may share it.
        // Deliberate trade (M3 review): ordering and availability over wall-clock accuracy.
        // After a backwards step, postings keep flowing, stamped ahead of the corrected
        // clock until real time overtakes the high-water mark — a fast clock mis-stamps
        // with or without the clamp, and the alternative (refusing to post until the clock
        // catches up) would turn clock skew into an outage on the money path.
        Instant postedAt = clock.instant();
        for (AccountBalance balance : locked) {
            if (balance.postingCount() > 0) {
                Instant floor = balance.updatedAt().plus(1, ChronoUnit.MICROS);
                if (floor.isAfter(postedAt)) {
                    postedAt = floor;
                }
            }
        }
        EntryId entryId = new EntryId(ids.nextId());
        List<PostingId> postingIds = new ArrayList<>(draft.legs().size());
        for (int i = 0; i < draft.legs().size(); i++) {
            postingIds.add(new PostingId(ids.nextId()));
        }
        JournalEntry entry = JournalEntry.post(entryId, entryType, draft, reversalOf, createdBy,
                idempotencyKey, postedAt, postingIds);
        journal.insert(entry);
        for (AccountBalance balance : locked) {
            balances.applyDelta(balance.accountId(), deltas.get(balance.accountId()),
                    legCounts.get(balance.accountId()), postedAt);
        }
        record(createdBy, idempotencyKey, requestHash, entry);
        return new PostingOutcome.Posted(entry);
    }
}
