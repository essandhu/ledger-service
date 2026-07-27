package io.github.essandhu.ledger.support.concurrent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.IntFunction;

/**
 * The stress-run machinery of the M5 concurrency suite — deliberately boring machinery around
 * sharp assertions. One shape for every workload: a fixed pool of workers,
 * all ARMED first (submitted and parked on a start gate), then RELEASED simultaneously to
 * maximize collision probability, then awaited under a bounded overall timeout where a hang or
 * overrun IS a failure — that bound is the deadlock detector for I17 (a real deadlock would
 * also surface earlier as SQLSTATE 40P01 after PostgreSQL's {@code deadlock_timeout}, but the
 * bound catches the undetectable cousins: lock-wait pileups, pool starvation, a stuck worker).
 *
 * <p><b>Honesty rule, enforced structurally</b>: workers return a VALUE
 * classifying their outcome — expected business rejections (an overdraft 422) are counted by
 * the test, not swallowed — and anything a worker THROWS is by definition unexpected and fails
 * the whole run with that throwable as the cause. Success is then asserted from database
 * state, never from client-side bookkeeping alone.
 *
 * <p><b>Sizing knobs</b> — thread count and iteration count are properties
 * so a nightly run can crank them: {@code -Dledger.concurrency.threads} and
 * {@code -Dledger.concurrency.iterations} override every suite's defaults; the Gradle
 * {@code concurrencyTest} task forwards both into the forked JVM, same contract as the
 * property harness's seed/iterations knobs. Malformed values fail loudly — a crank that
 * silently didn't crank is the property-harness seed lesson all over again.
 */
public final class StressRunner {

    /** Worker-count knob: {@code -Dledger.concurrency.threads=<n>} overrides every test's default. */
    public static final String THREADS_PROPERTY = "ledger.concurrency.threads";

    /** Per-worker iteration knob: {@code -Dledger.concurrency.iterations=<n>} overrides every test's default. */
    public static final String ITERATIONS_PROPERTY = "ledger.concurrency.iterations";

    private StressRunner() {
    }

    /** The effective worker count: the override when set, otherwise {@code defaultThreads}. */
    public static int threads(int defaultThreads) {
        return knob(THREADS_PROPERTY, defaultThreads);
    }

    /** The effective per-worker iteration count: the override when set, otherwise {@code defaultIterations}. */
    public static int iterations(int defaultIterations) {
        return knob(ITERATIONS_PROPERTY, defaultIterations);
    }

    /**
     * The hang-detector budget for a run of {@code totalOperations} — 60 s at the default
     * sizings, scaling at a deliberately pessimistic 10 lock-serialized operations/second
     * beyond them. The bound must scale with the knobs or the advertised nightly crank
     * ({@code -Dledger.concurrency.iterations=500}, say) would overrun a fixed bound doing
     * perfectly healthy work — a false deadlock verdict, the worst failure a proof suite can
     * emit. 10 ops/s is far below any observed rate (the hammers sustain hundreds/s), so an
     * overrun still means a hang, while a genuine deadlock on a cranked run is detected in
     * minutes, not hours.
     */
    public static Duration bound(long totalOperations) {
        return Duration.ofSeconds(Math.max(60, totalOperations / 10));
    }

    /**
     * Arms {@code workers} tasks on a fixed pool of the same size, releases them simultaneously,
     * and awaits completion within {@code timeout}. Returns every worker's value in worker-index
     * order. Throws {@link AssertionError} if any worker threw (first offender attached as the
     * cause, honesty rule) or if the run overran the bound (the I17 hang detector).
     *
     * @param taskForWorker builds worker {@code i}'s job; workers must classify EXPECTED
     *        failures into their return value and let anything unexpected propagate
     */
    public static <R> List<R> run(int workers, Duration timeout, IntFunction<Worker<R>> taskForWorker) {
        if (workers < 1) {
            throw new IllegalArgumentException("workers must be >= 1, got " + workers);
        }
        // One platform thread per worker: the whole point is genuinely simultaneous JDBC
        // contention, so no worker may wait for a pool slot behind another's blocking call.
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            CountDownLatch armed = new CountDownLatch(workers);
            CountDownLatch startGate = new CountDownLatch(1);
            List<Future<R>> futures = new ArrayList<>(workers);
            for (int i = 0; i < workers; i++) {
                Worker<R> worker = taskForWorker.apply(i);
                futures.add(pool.submit(() -> {
                    armed.countDown();
                    startGate.await();
                    return worker.work();
                }));
            }
            long deadline = System.nanoTime() + timeout.toNanos();
            try {
                // All workers must be parked on the gate before it opens — otherwise "released
                // simultaneously" would silently degrade to "released as submitted".
                if (!armed.await(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
                    throw new AssertionError(
                            "stress workers failed to arm within " + timeout + " — pool starvation before the run even began");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while arming stress workers", interrupted);
            }
            startGate.countDown();
            List<R> results = new ArrayList<>(workers);
            for (int i = 0; i < futures.size(); i++) {
                try {
                    long remaining = deadline - System.nanoTime();
                    // Zero or negative remaining budget must still poll (get(0) = immediate):
                    // workers that DID finish in time may all have completed even if collection
                    // reaches the last future at the bound's edge.
                    results.add(futures.get(i).get(Math.max(remaining, 0), TimeUnit.NANOSECONDS));
                } catch (TimeoutException hang) {
                    throw new AssertionError(
                            ("stress run overran its %s bound with worker %d (and possibly others) unfinished — "
                                    + "a hang or unbounded lock wait IS the failure (I17)")
                                    .formatted(timeout, i), hang);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("interrupted awaiting stress worker " + i, interrupted);
                } catch (java.util.concurrent.ExecutionException failed) {
                    throw new AssertionError(
                            ("stress worker %d threw — unexpected by definition (the stress-suite rules' honesty rule: "
                                    + "expected rejections are RETURN VALUES, anything thrown fails the run)")
                                    .formatted(i), failed.getCause());
                }
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    /** A worker's job: return a classification of what happened; throw only the unexpected. */
    @FunctionalInterface
    public interface Worker<R> {
        R work() throws Exception;
    }

    private static int knob(String property, int defaultValue) {
        if (defaultValue < 1) {
            throw new IllegalArgumentException(property + " default must be >= 1, got " + defaultValue);
        }
        String raw = System.getProperty(property);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(raw.trim());
        } catch (NumberFormatException notAnInt) {
            throw new IllegalArgumentException(
                    "-D" + property + " must be a positive int, got: " + raw, notAnInt);
        }
        if (parsed < 1) {
            throw new IllegalArgumentException("-D" + property + " must be >= 1, got: " + raw);
        }
        return parsed;
    }
}
