package io.github.essandhu.ledger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
import io.github.essandhu.ledger.application.port.in.IdempotencyKeyConflict;
import io.github.essandhu.ledger.application.port.in.PostJournalEntryUseCase;
import io.github.essandhu.ledger.application.port.in.PostJournalEntryUseCase.PostEntryCommand;
import io.github.essandhu.ledger.application.port.in.PostingOutcome;
import io.github.essandhu.ledger.domain.model.AccountId;
import io.github.essandhu.ledger.domain.model.AccountType;
import io.github.essandhu.ledger.domain.model.CurrencyCode;
import io.github.essandhu.ledger.domain.model.EntryDraft;
import io.github.essandhu.ledger.domain.model.Money;
import io.github.essandhu.ledger.support.LedgerIntegrationTest;
import io.github.essandhu.ledger.support.property.Gen;
import io.github.essandhu.ledger.support.property.Iterations;
import io.github.essandhu.ledger.support.property.Property;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * I9 as a universally-quantified property through the real stack (ADR-0004):
 * for ARBITRARY canonically-distinct command pairs under one key, the second request raises
 * the conflict and provably has zero side effects — no new journal rows, snapshots untouched.
 * Companion property (the no-false-conflict half): a structurally REBUILT but equal command
 * replays rather than conflicts, whatever the generated payload. Distinctness is generated
 * across every canonical dimension — amount, description, and leg ORDER (reordered legs are a
 * different command by ADR-0004's explicit choice).
 *
 * <p>Same discipline as the sibling DB property suites: pools built once up front, generation
 * a pure function of the rng ({@code -Dledger.property.seed} replays), reduced default
 * iterations for the PostgreSQL round-trip, and per-iteration UUID keys — run-unique on
 * purpose, because an rng-derived key would replay on seed replay instead of posting.
 */
@LedgerIntegrationTest
@DisplayName("I9 (end-to-end property): distinct payloads under one key conflict with zero side effects; equal payloads replay")
class IdempotencyPropertyIntegrationTest {

    private static final String REDUCED_ITERATIONS = "25";
    private static final String CREATED_BY = "idem-property-" + UUID.randomUUID();
    private static final long LEG_BOUND = 1_000_000_000L;
    private static final CurrencyCode EUR = new CurrencyCode("EUR");

    @Autowired
    private CreateAccountUseCase createAccount;

    @Autowired
    private PostJournalEntryUseCase postEntry; // resolves to the metered decorator (@Primary)

    @Autowired
    private DataSource dataSource;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /** A generated I9 case: a balanced base payload and a canonically-distinct tampering. */
    private record Case(String description, long amount, boolean swapAccounts,
            Tampering tampering) {
    }

    private enum Tampering {
        AMOUNT_CHANGED, DESCRIPTION_CHANGED, LEGS_REORDERED
    }

    private static Gen<Case> cases() {
        Gen<String> descriptions = Gen.oneOf(
                Gen.constant(null), Gen.constant("prop-i9"), Gen.constant("prop-i9-alt"));
        Gen<Long> amounts = Gen.longs(1, LEG_BOUND);
        Gen<Boolean> swaps = Gen.oneOf(Gen.constant(false), Gen.constant(true));
        Gen<Tampering> tamperings = Gen.oneOf(
                Gen.constant(Tampering.AMOUNT_CHANGED),
                Gen.constant(Tampering.DESCRIPTION_CHANGED),
                Gen.constant(Tampering.LEGS_REORDERED));
        return descriptions.flatMap(description -> amounts.flatMap(amount ->
                swaps.flatMap(swap -> tamperings.map(tampering ->
                        new Case(description, amount, swap, tampering)))));
    }

    @Test
    @DisplayName("I9: the tampered twin conflicts and writes nothing; the faithful twin replays")
    void distinct_payloads_conflict_equal_payloads_replay() {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
                CREATED_BY, "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_LEDGER_ADMIN"),
                        new SimpleGrantedAuthority("ROLE_LEDGER_WRITE"))));
        AccountId a = poolAccount("idem-prop-a");
        AccountId b = poolAccount("idem-prop-b");
        List<AccountId> pool = List.of(a, b);

        Iterations.withReducedDefault(REDUCED_ITERATIONS, () -> Property.check(cases(), c -> {
            String key = UUID.randomUUID().toString();
            AccountId debit = c.swapAccounts() ? b : a;
            AccountId credit = c.swapAccounts() ? a : b;
            PostEntryCommand base = command(c.description(), debit, credit, c.amount(), key);

            PostingOutcome first = postEntry.postEntry(base);
            assertThat(first).isInstanceOf(PostingOutcome.Posted.class);
            long entriesAfterFirst = entryCount();
            List<long[]> snapshotsAfterFirst = snapshots(pool);

            // The canonically-distinct twin: same key, provably different canonical form.
            PostEntryCommand tampered = switch (c.tampering()) {
                case AMOUNT_CHANGED -> command(c.description(), debit, credit,
                        c.amount() == LEG_BOUND ? c.amount() - 1 : c.amount() + 1, key);
                case DESCRIPTION_CHANGED -> command(
                        c.description() == null ? "tampered" : c.description() + "-tampered",
                        debit, credit, c.amount(), key);
                case LEGS_REORDERED -> new PostEntryCommand(c.description(), List.of(
                        new EntryDraft.Leg(credit, Money.of(-c.amount(), EUR)),
                        new EntryDraft.Leg(debit, Money.of(c.amount(), EUR))),
                        CREATED_BY, key);
            };
            assertThatThrownBy(() -> postEntry.postEntry(tampered))
                    .isInstanceOf(IdempotencyKeyConflict.class);
            assertThat(entryCount()).as("conflict wrote no entry").isEqualTo(entriesAfterFirst);
            assertThat(snapshots(pool)).as("conflict moved no balance")
                    .usingRecursiveComparison().isEqualTo(snapshotsAfterFirst);

            // No false conflicts: an EQUAL command — rebuilt from scratch, as a retried
            // request would be after re-parsing — replays the original.
            PostingOutcome replay = postEntry.postEntry(
                    command(c.description(), debit, credit, c.amount(), key));
            assertThat(replay).isInstanceOf(PostingOutcome.Replayed.class);
            assertThat(entryCount()).isEqualTo(entriesAfterFirst);
        }));
    }

    private PostEntryCommand command(String description, AccountId debit, AccountId credit,
            long amount, String key) {
        return new PostEntryCommand(description, List.of(
                new EntryDraft.Leg(debit, Money.of(amount, EUR)),
                new EntryDraft.Leg(credit, Money.of(-amount, EUR))),
                CREATED_BY, key);
    }

    private AccountId poolAccount(String label) {
        return createAccount.create(new CreateAccountCommand(
                label + "-" + UUID.randomUUID(), EUR, AccountType.ASSET, true)).id();
    }

    private long entryCount() {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT count(*) FROM journal_entry WHERE created_by = ?")) {
            select.setString(1, CREATED_BY);
            try (ResultSet row = select.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<long[]> snapshots(List<AccountId> accounts) {
        List<long[]> result = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT balance, posting_count FROM account_balance WHERE account_id = ?")) {
            for (AccountId account : accounts) {
                select.setObject(1, account.value());
                try (ResultSet row = select.executeQuery()) {
                    assertThat(row.next()).isTrue();
                    result.add(new long[] {row.getLong("balance"), row.getLong("posting_count")});
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return result;
    }
}
