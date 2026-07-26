package io.github.essandhu.ledger.adapter.persistence;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.essandhu.ledger.application.port.out.AccountRepository;
import io.github.essandhu.ledger.application.port.out.BalanceRepository;
import io.github.essandhu.ledger.domain.model.Account;
import io.github.essandhu.ledger.domain.model.AccountBalance;
import io.github.essandhu.ledger.domain.model.AccountId;
import io.github.essandhu.ledger.domain.model.AccountType;
import io.github.essandhu.ledger.domain.model.CurrencyCode;
import io.github.essandhu.ledger.support.LedgerIntegrationTest;
import io.github.essandhu.ledger.support.property.Gen;
import io.github.essandhu.ledger.support.property.Property;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The M5 half of the ADR-0003 lock-order proof, promised by name in the
 * {@link BalanceRepository#lockBalances} and {@code lockAllInCanonicalOrder} javadocs: the
 * lock query imposes the canonical bytewise UUID order on ARBITRARY inputs — generated ids
 * (the whole 128-bit space, so roughly half the draws have the signed/unsigned-disagreement
 * top bit set), arbitrary arrival order, duplicates, and unknown ids mixed in.
 * {@link BalanceLockIntegrationTest} pinned four fixed adversarial ids at M2; this property
 * universally quantifies the same claim, and because the expectation is computed with the
 * Java-side {@link BalanceRepository#UUID_BYTEWISE_ORDER} while the rows come back in the
 * database's {@code ORDER BY account_id} order, every iteration is also an agreement proof
 * between the two definitions — the exact drift (a mirror quietly reverting to signed
 * {@link UUID#compareTo}) that would silently re-admit deadlocks.
 *
 * <p>House DB-property discipline: the account pool is built once up front (I/O is not
 * generation), ids and arrival orders are pure functions of the seeded rng
 * ({@code -Dledger.property.seed} replays), rows carry marker names, and no assertion
 * quantifies over rows this class did not create. Iterations stay at the harness default —
 * each iteration is a single locked SELECT in its own short transaction, and the value of
 * this property IS its sweep across the UUID space.
 */
@LedgerIntegrationTest
@DisplayName("ADR-0003 (M5): the lock query orders ARBITRARY account-id inputs canonically — generated ids, duplicates, unknowns")
class LockOrderPropertyIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-07-26T10:00:00Z");
    /** 32 accounts × forced top bytes 8 apart sweep 0x00–0xF8, so every iteration's subset
     * crosses the 0x80 signed/unsigned boundary with near certainty. */
    private static final int POOL_SIZE = 32;

    @Autowired
    private BalanceRepository balances;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private TransactionTemplate transactionTemplate;

    /** A generated case: an arrival-ordered id list mixing pool members (with duplicates)
     * and unknown ids; {@code poolPicks} names the indexes for a readable counterexample. */
    private record Arrival(List<Integer> poolPicks, List<AccountId> input) {
    }

    @Test
    @DisplayName("lockBalances(arbitrary arrival order + duplicates + unknowns) returns exactly the known ids, deduplicated, in canonical bytewise order")
    void lock_query_orders_arbitrary_inputs_canonically() {
        List<AccountId> pool = buildPool();

        Property.check(arrivals(pool), arrival -> {
            List<AccountBalance> locked = transactionTemplate.execute(
                    tx -> balances.lockBalances(arrival.input()));

            // The expectation is computed ENTIRELY Java-side: known ids, deduplicated, sorted
            // by UUID_BYTEWISE_ORDER. The database independently produced its ORDER BY
            // account_id order — equality IS the Java-vs-PostgreSQL agreement proof.
            Set<AccountId> poolMembers = new LinkedHashSet<>(pool);
            List<AccountId> expected = arrival.input().stream()
                    .filter(poolMembers::contains)
                    .distinct()
                    .sorted(BalanceRepository.CANONICAL_ORDER)
                    .toList();
            assertThat(locked).extracting(AccountBalance::accountId)
                    .as("one total order, whatever arrived: %s", arrival.poolPicks())
                    .containsExactlyElementsOf(expected);
        });
    }

    /**
     * Pool ids are random in their low 120 bits (additive-safe on the shared schema) with the
     * top byte FORCED to sweep the full range — uniform 128-bit draws would already set the
     * top bit half the time, but the forced sweep guarantees every run holds ids on both
     * sides of 0x80 and pins a deterministic spread of the order's most significant byte.
     */
    private List<AccountId> buildPool() {
        List<AccountId> pool = poolIds();
        for (AccountId id : pool) {
            Account account = Account.open(id, "lock-order-prop-" + id.value(),
                    new CurrencyCode("EUR"), AccountType.ASSET, false, T0);
            transactionTemplate.executeWithoutResult(tx -> {
                accounts.insert(account);
                balances.insertZero(id, T0);
            });
        }
        return pool;
    }

    private static List<AccountId> poolIds() {
        List<AccountId> ids = new ArrayList<>(POOL_SIZE);
        for (int i = 0; i < POOL_SIZE; i++) {
            UUID random = UUID.randomUUID();
            long msb = (random.getMostSignificantBits() & 0x00FF_FFFF_FFFF_FFFFL)
                    | ((long) (i * 8) << 56);
            ids.add(new AccountId(new UUID(msb, random.getLeastSignificantBits())));
        }
        return ids;
    }

    /** 1–8 pool picks WITH repetition plus 0–2 unknown rng-derived ids, then a generated
     * arrival order (random-position insertion) — all a pure function of the iteration rng. */
    private static Gen<Arrival> arrivals(List<AccountId> pool) {
        return rng -> {
            int picks = rng.nextInt(1, 9);
            List<Integer> poolPicks = new ArrayList<>(picks);
            List<AccountId> chosen = new ArrayList<>();
            for (int i = 0; i < picks; i++) {
                int index = rng.nextInt(pool.size());
                poolPicks.add(index);
                chosen.add(pool.get(index));
            }
            int unknowns = rng.nextInt(0, 3);
            for (int i = 0; i < unknowns; i++) {
                // Fresh 128 random bits: cannot name a pool row, and a collision with any
                // OTHER test's random account id is beyond astronomically unlikely.
                chosen.add(new AccountId(new UUID(rng.nextLong(), rng.nextLong())));
            }
            List<AccountId> input = new ArrayList<>(chosen.size());
            for (AccountId id : chosen) {
                input.add(rng.nextInt(input.size() + 1), id);
            }
            return new Arrival(List.copyOf(poolPicks), List.copyOf(input));
        };
    }

    /** The fixture must have teeth: across the pool, the signed order and the canonical order
     * must disagree — otherwise the property could pass with a compareTo-sorted mirror.
     * Pure id arithmetic, so no rows are inserted here. */
    @Test
    @DisplayName("fixture teeth: the generated pool distinguishes signed UUID.compareTo from the canonical bytewise order")
    void pool_distinguishes_signed_from_bytewise_order() {
        List<AccountId> pool = poolIds();
        List<UUID> signed = pool.stream().map(AccountId::value).sorted().toList();
        List<UUID> bytewise = pool.stream().map(AccountId::value)
                .sorted(BalanceRepository.UUID_BYTEWISE_ORDER).toList();
        assertThat(signed)
                .as("top bytes ≥ 0x80 exist, so the two orders MUST differ — if they agree, "
                        + "this suite has lost its teeth")
                .isNotEqualTo(bytewise);
    }
}
