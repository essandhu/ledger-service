package io.github.essandhu.ledger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import io.github.essandhu.ledger.application.port.in.PostJournalEntryUseCase;
import io.github.essandhu.ledger.application.port.in.PostJournalEntryUseCase.PostEntryCommand;
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

/**
 * The thin end-to-end property set (TEST-STRATEGY §2.1): generated balanced entry batches
 * posted through the REAL use-case beans — security proxy, transaction, metered decorator,
 * ordered locking, JPA adapter, PostgreSQL — with I4 and I5 re-proven by raw SQL after every
 * batch. The unit-level properties pin the arithmetic; this suite pins that the arithmetic
 * SURVIVES the stack: if the adapter ever double-applied a delta, dropped a leg, or bumped the
 * wrong snapshot, the very first batch would say so.
 *
 * <p>Scope discipline: the properties quantify ONLY over this test's own marker accounts
 * (shared context, TEST-STRATEGY §2) — every generated entry touches only the pool, so
 * per-currency zero over the pool's postings IS global conservation restricted to the rows
 * this test may quantify over. Pool accounts allow negative balances on purpose: I6 rejection
 * is someone else's proof; conservation must not flake on an overdraft.
 *
 * <p>Generation follows the harness determinism contract (ADR-0005): account POOLS are created
 * once up front (I/O is not generation), the generator only picks from them via the supplied
 * rng, so {@code -Dledger.property.seed} replays the exact batch sequence. Shrinking re-posts
 * candidate batches — side effects on the shared schema, accepted: shrinking only runs on
 * failure, candidates stay balanced over the same pool, and diagnosis outranks tidiness there.
 */
@LedgerIntegrationTest
@DisplayName("I4/I5 (end-to-end property): generated balanced batches conserve value through the real posting engine")
class PostingConservationPropertyIntegrationTest {

    /** ADR-0005's default is 200 — right for pure-domain properties, hostile for ones that
     * round-trip PostgreSQL per iteration. TEST-STRATEGY §2.1 calls this set "thin": N=25. */
    private static final String REDUCED_ITERATIONS = "25";

    private static final String CREATED_BY = "conservation-property";

    /** Leg-amount bound, mirroring {@code Gens.LEG_BOUND}: sums stay far from 64-bit edges by
     * arithmetic, not luck — the generator must be incapable of manufacturing its own
     * {@code AmountOverflow}, or the conservation property would flake (ADR-0005). */
    private static final long LEG_BOUND = 1_000_000_000L;

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

    @Test
    @DisplayName("I4 + I5: after every generated batch, snapshots equal SUM/COUNT and every currency nets to zero")
    void generated_balanced_batches_conserve_value() {
        // The real @PreAuthorize proxies are on the tested path — authenticate accordingly
        // (ADMIN to build the pool, WRITE to post through it).
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
                CREATED_BY, "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_LEDGER_ADMIN"),
                        new SimpleGrantedAuthority("ROLE_LEDGER_WRITE"))));
        Map<CurrencyCode, List<AccountId>> pools = createPools();
        List<AccountId> allAccounts = pools.values().stream().flatMap(List::stream).toList();

        Gen<List<List<EntryDraft.Leg>>> batches = balancedLegsOver(pools).listOf(1, 3);
        Iterations.withReducedDefault(REDUCED_ITERATIONS, () -> Property.check(batches, batch -> {
            for (List<EntryDraft.Leg> legs : batch) {
                // M4: fresh UUID key per posting (run-unique like the account markers — an
                // rng-derived key would REPLAY on seed replay instead of posting).
                postEntry.postEntry(new PostEntryCommand("prop-conservation", legs, CREATED_BY,
                        UUID.randomUUID().toString()));
            }
            assertI4(allAccounts);
            assertI5(allAccounts);
        }));
    }

    /** Three allow-negative accounts per currency, types cycled — JPY (exponent 0) and BHD
     * (exponent 3) included so no two-decimal assumption survives (TEST-STRATEGY §2.2). */
    private Map<CurrencyCode, List<AccountId>> createPools() {
        Map<CurrencyCode, List<AccountId>> pools = new LinkedHashMap<>();
        AccountType[] types = AccountType.values();
        int typeIndex = 0;
        for (String code : List.of("EUR", "USD", "JPY", "BHD")) {
            CurrencyCode currency = new CurrencyCode(code);
            List<AccountId> pool = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                pool.add(createAccount.create(new CreateAccountCommand(
                        "conservation-prop-" + UUID.randomUUID(), currency,
                        types[typeIndex++ % types.length], true)).id());
            }
            pools.put(currency, List.copyOf(pool));
        }
        return pools;
    }

    /**
     * {@code Gens.balancedLegs}' construction — k−1 random nonzero legs plus the closing leg,
     * nudged if the closing leg would be zero — but drawing accounts from the REAL pool of the
     * leg's currency (an account holds exactly one currency, PLAN §4.1), so every generated
     * draft is postable and conservation is the only thing on trial.
     */
    private static Gen<List<EntryDraft.Leg>> balancedLegsOver(Map<CurrencyCode, List<AccountId>> pools) {
        List<CurrencyCode> currencies = List.copyOf(pools.keySet());
        return rng -> {
            int currencyCount = rng.nextInt(1, 3); // 1..2 currencies per entry
            int first = rng.nextInt(currencies.size());
            List<CurrencyCode> chosen = new ArrayList<>();
            chosen.add(currencies.get(first));
            if (currencyCount == 2) {
                int offset = rng.nextInt(1, currencies.size()); // always a distinct second
                chosen.add(currencies.get((first + offset) % currencies.size()));
            }
            List<EntryDraft.Leg> legs = new ArrayList<>();
            for (CurrencyCode currency : chosen) {
                List<AccountId> pool = pools.get(currency);
                int legCount = rng.nextInt(2, 6); // 2..5 legs per currency
                long[] amounts = new long[legCount - 1];
                long sum = 0;
                for (int i = 0; i < legCount - 1; i++) {
                    long draw = rng.nextLong(-LEG_BOUND, LEG_BOUND + 1);
                    amounts[i] = draw == 0 ? 1 : draw;
                    sum = Math.addExact(sum, amounts[i]);
                }
                if (sum == 0) { // the closing leg must stay nonzero (I2)
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

    /** I4: snapshot = SUM(amount) AND posting_count = COUNT(*), one statement per account —
     * exactly M6 reconciliation's comparison (ADR-0002). */
    private void assertI4(List<AccountId> accounts) {
        for (AccountId account : accounts) {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement select = connection.prepareStatement("""
                         SELECT b.balance, b.posting_count,
                                COALESCE(SUM(p.amount), 0) AS posted_sum,
                                COUNT(p.id) AS posted_count
                         FROM account_balance b
                         LEFT JOIN posting p ON p.account_id = b.account_id
                         WHERE b.account_id = ?
                         GROUP BY b.balance, b.posting_count
                         """)) {
                select.setObject(1, account.value());
                try (ResultSet row = select.executeQuery()) {
                    assertThat(row.next()).as("snapshot row for " + account.value()).isTrue();
                    assertThat(row.getLong("balance"))
                            .as("I4: snapshot balance = SUM(amount) for " + account.value())
                            .isEqualTo(row.getLong("posted_sum"));
                    assertThat(row.getLong("posting_count"))
                            .as("I4: posting_count = COUNT(*) for " + account.value())
                            .isEqualTo(row.getLong("posted_count"));
                }
            } catch (SQLException e) {
                throw new IllegalStateException("I4 probe failed for " + account.value(), e);
            }
        }
    }

    /** I5 over the pool: per currency, the postings sum to exactly zero. */
    private void assertI5(List<AccountId> accounts) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement("""
                     SELECT currency, SUM(amount) AS total
                     FROM posting
                     WHERE account_id = ANY (?)
                     GROUP BY currency
                     """)) {
            select.setArray(1, connection.createArrayOf(
                    "uuid", accounts.stream().map(AccountId::value).toArray()));
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    assertThat(rows.getLong("total"))
                            .as("I5: %s must net to zero".formatted(rows.getString("currency")))
                            .isZero();
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("I5 probe failed", e);
        }
    }
}
