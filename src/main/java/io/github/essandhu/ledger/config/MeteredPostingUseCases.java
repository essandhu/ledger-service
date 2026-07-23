package io.github.essandhu.ledger.config;

import java.util.Map;
import java.util.function.Supplier;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import io.github.essandhu.ledger.application.port.in.PostJournalEntryUseCase;
import io.github.essandhu.ledger.application.port.in.ReverseEntryUseCase;
import io.github.essandhu.ledger.application.port.in.TransferFundsUseCase;
import io.github.essandhu.ledger.application.service.PostingService;
import io.github.essandhu.ledger.domain.error.AccountClosed;
import io.github.essandhu.ledger.domain.error.AccountFrozen;
import io.github.essandhu.ledger.domain.error.AmountOverflow;
import io.github.essandhu.ledger.domain.error.CurrencyMismatch;
import io.github.essandhu.ledger.domain.error.EntryAlreadyReversed;
import io.github.essandhu.ledger.domain.error.OverdraftViolation;
import io.github.essandhu.ledger.domain.error.TooFewPostings;
import io.github.essandhu.ledger.domain.error.UnbalancedEntry;
import io.github.essandhu.ledger.domain.error.UnknownPostingAccount;
import io.github.essandhu.ledger.domain.error.ZeroAmountPosting;
import io.github.essandhu.ledger.domain.model.EntryType;
import io.github.essandhu.ledger.domain.model.JournalEntry;

/**
 * Micrometer decorator over the three money-moving ports (PLAN §8). It lives HERE, in config,
 * and not in the application layer: the ArchUnit rule
 * {@code application_is_spring_free_except_transactions} deliberately carries no micrometer
 * allowance, and widening it for metrics would trade the framework-free core away for a
 * convenience (I14) — so observability wraps the service from the outside, in the same package
 * that wires it. Wrapping OUTSIDE the {@code @Transactional}/{@code @PreAuthorize} proxies
 * means {@code ledger.posting.duration} measures what the caller experiences, transaction
 * commit included. The third posting metric, {@code ledger.posting.lock.wait}, lives in the
 * persistence adapter around the FOR UPDATE query itself, where the waiting happens.
 *
 * <p>Vocabulary discipline (PLAN §8, pinned by the ProblemTypes javadoc): the {@code reason}
 * tag on {@code ledger.posting.rejected} IS the problem-type slug — one vocabulary for
 * problems and metrics, so a dashboard and a client error dereference the same names. Only the
 * mapped domain rejections count as {@code outcome=rejected}; anything else — the 404 of a
 * missing reversal target, 400-family input shape, access denials, genuine bugs — rethrows
 * with its timer sample discarded, keeping the rejected series and the reason counter aligned.
 * {@code account-balance-not-zero} is absent by design: it belongs to the close use case,
 * which these ports do not carry.
 */
final class MeteredPostingUseCases
        implements PostJournalEntryUseCase, TransferFundsUseCase, ReverseEntryUseCase {

    private static final String DURATION = "ledger.posting.duration";
    private static final String REJECTED = "ledger.posting.rejected";

    /**
     * Domain rejection type → problem slug (PLAN §5). Exact classes — no hierarchies exist.
     * Package-private, not private, so the vocabulary proof (MeteredPostingUseCasesTest) can
     * assert these hand-written strings 1:1 against the ProblemTypes posting family — the
     * one-vocabulary contract both javadocs promise.
     */
    static final Map<Class<? extends RuntimeException>, String> REASONS = Map.ofEntries(
            Map.entry(UnbalancedEntry.class, "unbalanced-entry"),
            Map.entry(TooFewPostings.class, "too-few-postings"),
            Map.entry(ZeroAmountPosting.class, "zero-amount-posting"),
            Map.entry(CurrencyMismatch.class, "currency-mismatch"),
            Map.entry(AmountOverflow.class, "amount-overflow"),
            Map.entry(OverdraftViolation.class, "overdraft"),
            Map.entry(AccountFrozen.class, "account-frozen"),
            Map.entry(AccountClosed.class, "account-closed"),
            Map.entry(UnknownPostingAccount.class, "unknown-account"),
            Map.entry(EntryAlreadyReversed.class, "entry-already-reversed"));

    private final PostingService delegate;
    private final MeterRegistry registry;

    MeteredPostingUseCases(PostingService delegate, MeterRegistry registry) {
        this.delegate = delegate;
        this.registry = registry;
    }

    @Override
    public JournalEntry postEntry(PostEntryCommand command) {
        return timed(EntryType.JOURNAL, () -> delegate.postEntry(command));
    }

    @Override
    public JournalEntry transfer(TransferCommand command) {
        return timed(EntryType.TRANSFER, () -> delegate.transfer(command));
    }

    @Override
    public JournalEntry reverse(ReverseCommand command) {
        return timed(EntryType.REVERSAL, () -> delegate.reverse(command));
    }

    private JournalEntry timed(EntryType entryType, Supplier<JournalEntry> operation) {
        Timer.Sample sample = Timer.start(registry);
        try {
            JournalEntry entry = operation.get();
            sample.stop(registry.timer(DURATION,
                    "entry_type", entryType.name(), "outcome", "posted"));
            return entry;
        } catch (RuntimeException e) {
            String reason = REASONS.get(e.getClass());
            if (reason != null) {
                sample.stop(registry.timer(DURATION,
                        "entry_type", entryType.name(), "outcome", "rejected"));
                registry.counter(REJECTED, "reason", reason).increment();
            }
            throw e;
        }
    }
}
