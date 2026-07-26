package io.github.essandhu.ledger.concurrency;

import java.util.List;
import java.util.SplittableRandom;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import io.github.essandhu.ledger.support.concurrent.StressRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * I17 — deadlock freedom (TEST-STRATEGY §4, workload b): sustained bidirectional transfers,
 * A→B and B→A simultaneously at randomized amounts — the opposing-direction traffic that
 * deadlocks any implementation acquiring locks in request order. A victim abort (SQLSTATE
 * 40P01 after {@code deadlock_timeout}) surfaces as a 500 that the honesty rule fails loudly,
 * and a hang of any other kind dies on the harness's bounded await. Every request must
 * terminate, and every one must terminate with a 201: both accounts allow negative balances,
 * so this workload has NO legitimate rejection.
 *
 * <p>Honesty about reach: in the SUT as built, ALL locks are taken by one statement whose
 * {@code ORDER BY account_id} imposes the canonical order regardless of bind order — so this
 * hammer proves I17 for the system as built and catches any regression to per-account
 * lock-as-you-go acquisition (the shape that CAN interleave), but it cannot detect removal of
 * the Java-side pre-sorts, whose bind order the single statement ignores. Those are policed
 * where they can be seen: the unit fake rejects unsorted callers, and the M5 lock-order
 * property pins the query's returned order for arbitrary inputs.
 *
 * <p>Afterwards, the §4 state checks: conservation across the pair (I5 — the two snapshots sum
 * to zero, as does Σ over all their postings), snapshot-equals-history for each account (I4),
 * and the PLAN §9 lock metric — the hammer's acquisitions all landed samples in
 * {@code ledger.posting.lock.wait}, the queue PLAN §8 promises makes contention measurable.
 */
@DisplayName("I17: bidirectional transfer hammering completes deadlock-free, conserving to zero")
class BidirectionalTransferConcurrencyTest extends ConcurrencyTestSupport {

    @Test
    @DisplayName("I17: A→B ∥ B→A at random amounts — all requests 201 within the bound, I5/I4 hold, lock waits measured")
    void bidirectional_transfers_complete_without_deadlock() {
        int threads = StressRunner.threads(8);
        int transfersPerThread = StressRunner.iterations(25);
        String subject = subject("i17-transferrer");
        String a = createAccount(marker("i17-a"), true);
        String b = createAccount(marker("i17-b"), true);

        long lockWaitsBefore = lockWaitCount();

        // Even workers push A→B, odd workers B→A — the opposing directions that deadlock
        // without the canonical sort. Amounts are drawn from a per-worker seeded rng: varied
        // like production traffic, deterministic per worker (the INTERLEAVING is the random
        // element under test; TEST-STRATEGY §1's replay discipline governs generation, and
        // thread schedules are inherently unreplayable — the invariant must hold for ALL of
        // them, which is exactly what makes this a hammer rather than a property).
        // Each worker returns its net contribution to A's raw balance (+x for A→B's source
        // debit, −x for B→A's target credit) so the final assertion is exact, not statistical.
        List<Long> netToA = StressRunner.run(threads,
                StressRunner.bound((long) threads * transfersPerThread), worker -> () -> {
            SplittableRandom amounts = new SplittableRandom(worker);
            boolean aToB = worker % 2 == 0;
            long net = 0;
            for (int i = 0; i < transfersPerThread; i++) {
                long amount = amounts.nextLong(1, 1_000);
                String key = "i17-%s-w%d-%d".formatted(subject, worker, i);
                MvcTestResult result = postTransfer(subject, key, aToB
                        ? transferJson(a, b, amount)
                        : transferJson(b, a, amount));
                if (result.getResponse().getStatus() != 201) {
                    // A 40P01 deadlock victim surfaces here as a 500 — the loudest possible
                    // failure of ADR-0003's ordering discipline (I17's SQLSTATE clause).
                    throw unexpectedResponse("transfer " + key, result);
                }
                net += aToB ? amount : -amount;
            }
            return net;
        });

        long expectedA = netToA.stream().mapToLong(Long::longValue).sum();
        long expectedEntries = (long) threads * transfersPerThread;

        // Exact final positions: A holds the net of all directions, B mirrors it (I5 over the
        // pair — nothing leaked, nothing minted), and every transfer committed exactly once.
        assertThat(balanceRow(a).balance()).as("A's snapshot = Σ of its debits and credits")
                .isEqualTo(expectedA);
        assertThat(balanceRow(b).balance()).as("I5: the pair conserves to zero")
                .isEqualTo(-expectedA);
        assertThat(entryCount(subject)).as("every request terminated in exactly one entry")
                .isEqualTo(expectedEntries);
        assertThat(balanceRow(a).postingCount() + balanceRow(b).postingCount())
                .as("two legs per transfer, none lost, none doubled")
                .isEqualTo(2 * expectedEntries);

        // I4 for both hot rows: snapshot equals the append-only history it summarizes.
        assertSnapshotEqualsPostings(a);
        assertSnapshotEqualsPostings(b);

        // PLAN §9 M5 'lock metrics': contention was MEASURED, not just survived (PLAN §8's
        // promise that hot-account queueing is observable); monotone >= — shared registry.
        assertThat(lockWaitCount())
                .as("ledger.posting.lock.wait recorded a sample per posting under the hammer")
                .isGreaterThanOrEqualTo(lockWaitsBefore + expectedEntries);
    }
}
