package io.github.essandhu.ledger.concurrency;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import io.github.essandhu.ledger.support.concurrent.StressRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * I7 — no lost updates (workload a): N threads × M unit deposits into ONE
 * hot account must land a balance increase of exactly N·M. This is the direct hammer on
 * ADR-0003's race #1: two transactions that read balance 100, each add 1, and both write 101
 * would leave the count short — the ordered {@code FOR UPDATE} serialization makes that
 * interleaving impossible, and losing even ONE unit out of N·M fails the exact-equality
 * assertion. Every deposit debits the hot account (+1 raw — an ASSET's natural balance rises)
 * and credits a single {@code allowNegative} funding account, so both rows are hot and every
 * pair of workers collides on every operation.
 *
 * <p>Defense-in-depth cross-check: I4 (snapshot = Σ postings) is asserted for both accounts
 * afterwards — the invariant catalogue notes a lost snapshot update would ALSO show up as
 * snapshot-vs-Σ divergence, so I7 and I4 confirm each other from independent directions
 * (the snapshot row vs the append-only posting history).
 */
@DisplayName("I7: N concurrent unit deposits increase the hot balance by exactly N (no lost updates)")
class DepositRaceConcurrencyTest extends ConcurrencyTestSupport {

    @Test
    @DisplayName("I7: N threads × M unit deposits → snapshot, Σ postings, and posting_count all read exactly N·M")
    void concurrent_unit_deposits_land_exactly_once_each() {
        int threads = StressRunner.threads(8);
        int depositsPerThread = StressRunner.iterations(25);
        String subject = subject("i7-depositor");
        String hot = createAccount(marker("i7-hot"), false);
        String funder = createAccount(marker("i7-funder"), true);

        long lockWaitsBefore = lockWaitCount();

        // Every worker fires M sequential unit deposits; all N workers are released at once.
        // Each deposit is its own idempotency key (they are distinct operations — I8's shared
        // key is the OTHER workload); a non-201 answer is unexpected by definition here, since
        // nothing in this workload can be legitimately rejected.
        List<Integer> posted = StressRunner.run(threads,
                StressRunner.bound((long) threads * depositsPerThread), worker -> () -> {
            for (int i = 0; i < depositsPerThread; i++) {
                String key = "i7-%s-w%d-%d".formatted(subject, worker, i);
                MvcTestResult result = postJournal(subject, key, journalJson(hot, funder, 1));
                if (result.getResponse().getStatus() != 201) {
                    throw unexpectedResponse("unit deposit " + key, result);
                }
            }
            return depositsPerThread;
        });

        long expected = (long) threads * depositsPerThread;
        assertThat(posted.stream().mapToLong(Integer::longValue).sum()).isEqualTo(expected);

        // The invariant, from database truth: EXACTLY N·M — one unit short is a lost update,
        // one unit over is a double-post; neither tolerance nor epsilon exists here.
        assertThat(balanceRow(hot).balance())
                .as("I7: hot snapshot = start(0) + N·M — a lost update reads short")
                .isEqualTo(expected);
        assertThat(postingSum(hot))
                .as("I7 from the posting history: N·M one-unit debits, none missing")
                .isEqualTo(new PostingSum(expected, expected));
        assertThat(balanceRow(funder).balance())
                .as("the funding side mirrors exactly (−N·M)")
                .isEqualTo(-expected);
        assertThat(entryCount(subject))
                .as("every deposit committed exactly once")
                .isEqualTo(expected);

        // I4 as the independent cross-check on both hot rows.
        assertSnapshotEqualsPostings(hot);
        assertSnapshotEqualsPostings(funder);

        // M5 'lock metrics': the hammer's acquisitions were all timed — the shared
        // registry only ever grows, so monotone >= keeps this additive-safe.
        assertThat(lockWaitCount())
                .as("ledger.posting.lock.wait sampled every posting's acquisition")
                .isGreaterThanOrEqualTo(lockWaitsBefore + expected);
    }
}
