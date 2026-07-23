package io.github.essandhu.ledger.support.fakes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import io.github.essandhu.ledger.application.port.out.BalanceRepository;
import io.github.essandhu.ledger.domain.model.AccountBalance;
import io.github.essandhu.ledger.domain.model.AccountId;

/**
 * Hand-written fake (TEST-STRATEGY §2.1: fakes over mock-framework stubs, so the port contract
 * is enforced, not just echoed): an in-memory snapshot store that POLICES the lock contract —
 * {@code lockBalances} rejects input that is not distinct and canonically ordered, because a
 * deadlock-prone service that merely echoes its input back must not pass its unit tests
 * (ADR-0003). Records every lock invocation with its exact id order, and every applied delta,
 * so lock discipline and "a rejected posting writes NOTHING" (ADR-0004) are assertable.
 */
public final class FakeBalanceRepository implements BalanceRepository {

    /** One recorded {@code applyDelta} invocation, exactly as the port received it. */
    public record AppliedDelta(AccountId accountId, long delta, long legCount, Instant now) {
    }

    private final Map<AccountId, AccountBalance> rows = new LinkedHashMap<>();
    private final List<List<AccountId>> lockInvocations = new ArrayList<>();
    private final List<AppliedDelta> appliedDeltas = new ArrayList<>();
    private int insertZeroCalls;

    @Override
    public List<AccountBalance> lockBalances(List<AccountId> idsInCanonicalOrder) {
        List<AccountId> ids = List.copyOf(idsInCanonicalOrder);
        lockInvocations.add(ids);
        for (int i = 1; i < ids.size(); i++) {
            // Strictly ascending = distinct AND canonically ordered in one check.
            if (CANONICAL_ORDER.compare(ids.get(i - 1), ids.get(i)) >= 0) {
                throw new IllegalArgumentException(
                        "lockBalances requires distinct ids in canonical order (ADR-0003), got " + ids);
            }
        }
        return ids.stream()
                .map(rows::get)
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    public Optional<AccountBalance> findCurrent(AccountId accountId) {
        // Lock-free by contract (no invocation recorded in lockInvocations — the M3 balance
        // read must never appear in a lock-order assertion).
        return Optional.ofNullable(rows.get(accountId));
    }

    @Override
    public void applyDelta(AccountId accountId, long delta, long legCount, Instant now) {
        AccountBalance current = rows.get(accountId);
        if (current == null) {
            throw new IllegalStateException("applyDelta on missing balance row " + accountId);
        }
        appliedDeltas.add(new AppliedDelta(accountId, delta, legCount, now));
        rows.put(accountId, new AccountBalance(accountId,
                Math.addExact(current.balance(), delta),
                current.postingCount() + legCount, now));
    }

    @Override
    public void insertZero(AccountId accountId, Instant createdAt) {
        AccountBalance zero = new AccountBalance(accountId, 0, 0, createdAt);
        if (rows.putIfAbsent(accountId, zero) != null) {
            throw new IllegalStateException("duplicate insertZero for " + accountId);
        }
        insertZeroCalls++;
    }

    /** Test-only seeding that bypasses the use-case layer (and the insertZero counter). */
    public void seed(AccountBalance balance) {
        rows.put(balance.accountId(), balance);
    }

    public Optional<AccountBalance> balanceOf(AccountId accountId) {
        return Optional.ofNullable(rows.get(accountId));
    }

    /** Every lock acquisition, in call order, each with the exact id order the port received. */
    public List<List<AccountId>> lockInvocations() {
        return List.copyOf(lockInvocations);
    }

    public List<AppliedDelta> appliedDeltas() {
        return List.copyOf(appliedDeltas);
    }

    public int insertZeroCalls() {
        return insertZeroCalls;
    }
}
