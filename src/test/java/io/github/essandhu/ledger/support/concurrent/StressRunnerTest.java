package io.github.essandhu.ledger.support.concurrent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Meta-tests for {@link StressRunner} — the same discipline as the property harness
 * (ADR-0005): the machinery proves it can detect failure BEFORE any invariant depends on it.
 * A stress runner that silently serialized its workers, swallowed their exceptions, or waited
 * forever would make every M5 "proof" a green lie.
 */
class StressRunnerTest {

    @Test
    @DisplayName("workers run simultaneously, not as-submitted: all must meet at one barrier")
    void releasesAllWorkersSimultaneously() {
        int workers = 8;
        // The barrier trips only if all 8 workers are inside work() at the same time. A runner
        // that ran workers sequentially (pool of 1, or gate-per-submit) would park worker 0 on
        // the barrier forever and fail via the barrier's own timeout.
        CyclicBarrier collision = new CyclicBarrier(workers);
        List<Integer> results = StressRunner.run(workers, Duration.ofSeconds(10), i -> () -> {
            collision.await(5, TimeUnit.SECONDS);
            return i;
        });
        assertThat(results).containsExactlyElementsOf(IntStream.range(0, workers).boxed().toList());
    }

    @Test
    @DisplayName("a worker's throwable fails the run with the offender attached (honesty rule)")
    void workerThrowableFailsTheRun() {
        IllegalStateException defect = new IllegalStateException("unexpected by definition");
        assertThatThrownBy(() -> StressRunner.run(4, Duration.ofSeconds(10), i -> () -> {
            if (i == 2) {
                throw defect;
            }
            return i;
        }))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("honesty rule")
                .cause().isSameAs(defect);
    }

    @Test
    @DisplayName("a hung worker fails the run at the bound — the I17 hang detector")
    void hangFailsAtTheBound() {
        CountDownLatch never = new CountDownLatch(1);
        try {
            // 2 s, not milliseconds: the runner spends the SAME budget on arming, and a
            // sub-second bound could expire during a GC/scheduler stall before both workers
            // even reach the gate — failing with the arming message instead of "overran"
            // and flaking this meta-test on loaded CI. Worker 1 hangs forever, so any bound
            // proves the detection; 2 s merely prices the proof.
            assertThatThrownBy(() -> StressRunner.run(2, Duration.ofSeconds(2), i -> () -> {
                if (i == 1) {
                    never.await(); // parked until the runner gives up and interrupts the pool
                }
                return i;
            }))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("overran")
                    .hasCauseInstanceOf(TimeoutException.class);
        } finally {
            never.countDown();
        }
    }

    @Test
    @DisplayName("results come back in worker-index order, one per worker")
    void resultsAreCompletePerWorker() {
        List<String> results = StressRunner.run(6, Duration.ofSeconds(10), i -> () -> "worker-" + i);
        assertThat(results).containsExactly(
                "worker-0", "worker-1", "worker-2", "worker-3", "worker-4", "worker-5");
    }

    @Test
    @DisplayName("an expected-outcome classification is a return value, never an exception")
    void expectedOutcomesAreValues() {
        // The house pattern for every stress workload: catch the EXPECTED business rejection
        // inside the worker, classify it, and let the test count classifications afterwards.
        CyclicBarrier collision = new CyclicBarrier(3);
        List<String> outcomes = StressRunner.run(3, Duration.ofSeconds(10), i -> () -> {
            try {
                collision.await(5, TimeUnit.SECONDS);
                if (i == 0) {
                    throw new BrokenBarrierException("simulated expected rejection");
                }
                return "success";
            } catch (BrokenBarrierException expected) {
                return "rejected";
            }
        });
        assertThat(outcomes).containsExactly("rejected", "success", "success");
    }

    @Test
    @DisplayName("sizing knobs: defaults hold when unset; malformed overrides fail loudly")
    void sizingKnobs() {
        // Snapshot-and-restore, not clear: an IDE whole-suite run may share this JVM with the
        // stress suites AND carry a launch-time -Dledger.concurrency.* crank — clearing would
        // silently erase it for every test that runs later (the exact "crank that quietly
        // didn't crank" the runner's javadoc calls the worst outcome), and asserting defaults
        // without first clearing would fail under such a launch config.
        String priorThreads = System.getProperty(StressRunner.THREADS_PROPERTY);
        String priorIterations = System.getProperty(StressRunner.ITERATIONS_PROPERTY);
        try {
            System.clearProperty(StressRunner.THREADS_PROPERTY);
            System.clearProperty(StressRunner.ITERATIONS_PROPERTY);
            assertThat(StressRunner.threads(7)).isEqualTo(7);
            assertThat(StressRunner.iterations(25)).isEqualTo(25);

            System.setProperty(StressRunner.THREADS_PROPERTY, "12");
            System.setProperty(StressRunner.ITERATIONS_PROPERTY, "not-a-number");
            assertThat(StressRunner.threads(7)).isEqualTo(12);
            assertThatThrownBy(() -> StressRunner.iterations(25))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(StressRunner.ITERATIONS_PROPERTY);
        } finally {
            restore(StressRunner.THREADS_PROPERTY, priorThreads);
            restore(StressRunner.ITERATIONS_PROPERTY, priorIterations);
        }
    }

    private static void restore(String property, String prior) {
        if (prior == null) {
            System.clearProperty(property);
        } else {
            System.setProperty(property, prior);
        }
    }

    @Test
    @DisplayName("the hang-detector bound scales with the requested work — a cranked run never overruns doing healthy work")
    void boundScalesWithWork() {
        assertThat(StressRunner.bound(200)).isEqualTo(Duration.ofSeconds(60));   // defaults
        assertThat(StressRunner.bound(4_000)).isEqualTo(Duration.ofSeconds(400)); // nightly crank
    }
}
