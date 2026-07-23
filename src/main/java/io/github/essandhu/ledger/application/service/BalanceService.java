package io.github.essandhu.ledger.application.service;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

import io.github.essandhu.ledger.application.port.in.AccountNotFound;
import io.github.essandhu.ledger.application.port.in.BalanceView;
import io.github.essandhu.ledger.application.port.in.GetBalanceQuery;
import io.github.essandhu.ledger.application.port.in.GetStatementQuery;
import io.github.essandhu.ledger.application.port.in.StatementCursor;
import io.github.essandhu.ledger.application.port.in.StatementFilter;
import io.github.essandhu.ledger.application.port.in.StatementPage;
import io.github.essandhu.ledger.application.port.in.StatementSpec;
import io.github.essandhu.ledger.application.port.out.AccountRepository;
import io.github.essandhu.ledger.application.port.out.BalanceRepository;
import io.github.essandhu.ledger.application.port.out.JournalRepository;
import io.github.essandhu.ledger.domain.error.AmountOverflow;
import io.github.essandhu.ledger.domain.model.Account;
import io.github.essandhu.ledger.domain.model.AccountBalance;
import io.github.essandhu.ledger.domain.model.AccountId;
import io.github.essandhu.ledger.domain.model.Posting;

/**
 * Balance and statement queries (PLAN §5, M3) — the read-only complement of the posting
 * engine, implementing ADR-0002's split literally: current = the snapshot row, read
 * lock-free; as-of = derived from postings, never the snapshot. Registered by config wiring,
 * not component scanning; declarative {@code @Transactional} and {@code @PreAuthorize}
 * (LEDGER_READ on every method, PLAN §5) are its only Spring surface (I14). Reads serve
 * FROZEN and CLOSED accounts alike — lifecycle gates postings, not queries (PLAN §4.5).
 */
public class BalanceService implements GetBalanceQuery, GetStatementQuery {

    private final AccountRepository accounts;
    private final BalanceRepository balances;
    private final JournalRepository journal;

    public BalanceService(AccountRepository accounts, BalanceRepository balances,
            JournalRepository journal) {
        this.accounts = accounts;
        this.balances = balances;
        this.journal = journal;
    }

    @Override
    @PreAuthorize("hasRole('LEDGER_READ')")
    @Transactional(readOnly = true)
    public BalanceView current(AccountId id) {
        Account account = require(id);
        AccountBalance snapshot = balances.findCurrent(id)
                // Unreachable by construction (V3 backfill + create-tx insert): absence is
                // corruption, not a client error — fail loudly, same as the lifecycle path.
                .orElseThrow(() -> new IllegalStateException(
                        "account %s has no balance row (V2 forward-contract violated)"
                                .formatted(id.value())));
        return view(account, snapshot.balance(), snapshot.postingCount(), Optional.empty());
    }

    @Override
    @PreAuthorize("hasRole('LEDGER_READ')")
    @Transactional(readOnly = true)
    public BalanceView asOf(AccountId id, Instant at) {
        Objects.requireNonNull(at, "at");
        Account account = require(id);
        JournalRepository.PostingAggregate aggregate = journal.sumPostingsAsOf(id, at);
        return view(account, aggregate.sum(), aggregate.count(), Optional.of(at));
    }

    @Override
    @PreAuthorize("hasRole('LEDGER_READ')")
    @Transactional(readOnly = true)
    public StatementPage statement(AccountId id, StatementFilter filter, StatementSpec spec) {
        require(id);
        List<Posting> lines = journal.statementLines(id, filter, spec.after(), spec.limit());
        // The resume contract, pinned at M3 (see StatementPage): content → the last line's
        // position; no content → the request's own position, so tail-following is stateless.
        Optional<StatementCursor> next = lines.isEmpty()
                ? spec.after()
                : Optional.of(positionOf(lines.get(lines.size() - 1)));
        return new StatementPage(lines, next);
    }

    /** Path-addressed miss = the 404 {@link AccountNotFound}, never the payload's 422. */
    private Account require(AccountId id) {
        return accounts.findById(id).orElseThrow(() -> new AccountNotFound(id));
    }

    private static StatementCursor positionOf(Posting last) {
        return new StatementCursor(last.postedAt(), last.id());
    }

    private static BalanceView view(Account account, long raw, long postingCount,
            Optional<Instant> asOf) {
        long natural;
        try {
            natural = account.type().natural(raw);
        } catch (ArithmeticException overflow) {
            // Raw Long.MIN_VALUE on a credit-normal account (reachable: allow-negative skips
            // the overdraft floor) has no 64-bit natural — the accumulation point translates
            // the checked multiply's refusal into the 422 (ADR-0001), same as close/posting.
            throw new AmountOverflow(
                    "account %s natural balance has no 64-bit representation (ADR-0001: checked arithmetic rejects, never wraps)"
                            .formatted(account.id().value()));
        }
        return new BalanceView(account.id(), account.type(), account.currency(), raw, natural,
                postingCount, asOf);
    }
}
