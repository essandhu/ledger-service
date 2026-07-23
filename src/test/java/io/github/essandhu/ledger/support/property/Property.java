package io.github.essandhu.ledger.support.property;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.SplittableRandom;
import java.util.function.Consumer;

/**
 * The property runner of the in-repo harness (ADR-0005; see {@link Gen} for why the harness is
 * owned code rather than jqwik). {@link #check} runs an invariant over {@link #DEFAULT_ITERATIONS}
 * generated values and reports the first violation with everything needed to reproduce it —
 * because TEST-STRATEGY §1 treats an unreproducible failure message as a test defect in its own
 * right.
 *
 * <p><b>Seed discipline.</b> Each run has one root seed: taken from
 * {@code -Dledger.property.seed} when set (a replay), otherwise drawn fresh — the single place
 * ambient entropy enters the harness, and every failure message pins the value it drew.
 * Per-iteration randomness derives from the root via {@link SplittableRandom#split()}, one split
 * per iteration in order, so iteration {@code i} regenerates identically on replay regardless of
 * what any other iteration did. The failure message carries the root seed and the iteration; the
 * meta-tests ({@code PropertyHarnessTest}) parse a real failure message and replay it to prove
 * the loop is honest.
 *
 * <p><b>Iterations.</b> {@code -Dledger.property.iterations} raises (or lowers) the count — CI
 * can crank it without a code change (TEST-STRATEGY §2.2). Malformed values fail loudly rather
 * than being silently ignored: a replay that quietly does not replay is the worst outcome.
 *
 * <p><b>Shrinking, deliberately basic</b> (ADR-0005): numbers step toward zero, lists toward
 * fewer elements, by bounded greedy re-check passes — each accepted candidate is strictly
 * smaller AND still fails the invariant, so shrinking terminates and only ever reports true
 * counterexamples of the invariant. Two accepted limitations, straight from the ADR: values
 * without a numeric/list shape are not shrunk at all, and a shrunk value may lie outside the
 * generator's range (the shrinker explores the value space, not the generator's image — the
 * original counterexample is always reported alongside). If a counterexample is hard to read,
 * improve the generator, not the shrinker.
 */
public final class Property {

    /** Replay knob: {@code -Dledger.property.seed=<long>} reruns exactly the failing sequence. */
    public static final String SEED_PROPERTY = "ledger.property.seed";

    /** Iteration knob: {@code -Dledger.property.iterations=<n>} overrides {@link #DEFAULT_ITERATIONS}. */
    public static final String ITERATIONS_PROPERTY = "ledger.property.iterations";

    /** ADR-0005 contract: 200 iterations per property by default. */
    public static final int DEFAULT_ITERATIONS = 200;

    /** Upper bound on shrink-candidate re-checks per failure — termination insurance on top of
     * the strictly-decreasing measure. */
    private static final int SHRINK_BUDGET = 1_000;

    private Property() {
    }

    /**
     * Asserts {@code invariant} over {@code iterations} values drawn from {@code gen}. The
     * invariant expresses violation by throwing (AssertJ assertions are the house idiom); any
     * {@link Throwable} — including {@link AssertionError} — counts as a violation, so
     * invariants stay written in ordinary assertion style, independent of harness internals
     * (ADR-0005's mitigation against harness bugs masking defects).
     *
     * @throws AssertionError on the first violated iteration, with the original counterexample,
     *         the shrunk counterexample, the iteration number, and the root seed; the violating
     *         throwable is attached as the cause
     */
    public static <T> void check(Gen<T> gen, Consumer<? super T> invariant) {
        Objects.requireNonNull(gen, "gen");
        Objects.requireNonNull(invariant, "invariant");
        int iterations = iterations();
        long rootSeed = rootSeed();
        SplittableRandom root = new SplittableRandom(rootSeed);
        for (int iteration = 1; iteration <= iterations; iteration++) {
            // One split per iteration, in order — the whole replay contract hangs on this line.
            SplittableRandom iterationRng = root.split();
            T value;
            try {
                value = gen.generate(iterationRng);
            } catch (Throwable generatorFailure) {
                throw new AssertionError(
                        "generator threw at iteration " + iteration + "/" + iterations
                                + "\n  replay with -D" + SEED_PROPERTY + "=" + rootSeed,
                        generatorFailure);
            }
            Throwable violation = violationOf(invariant, value);
            if (violation != null) {
                T shrunk = shrink(value, invariant);
                throw new AssertionError(
                        "property falsified at iteration " + iteration + "/" + iterations
                                + "\n  counterexample: " + value
                                + "\n  shrunk to: " + shrunk
                                + "\n  replay with -D" + SEED_PROPERTY + "=" + rootSeed,
                        violation);
            }
        }
    }

    private static <T> Throwable violationOf(Consumer<? super T> invariant, T value) {
        try {
            invariant.accept(value);
            return null;
        } catch (Throwable violation) {
            // Throwable, not Exception: AssertionError is the normal way an AssertJ invariant
            // reports a violation, and it is an Error.
            return violation;
        }
    }

    /**
     * Greedy descent: repeatedly replaces the counterexample with the first strictly-smaller
     * candidate that still fails, until no candidate fails or the budget runs out.
     */
    private static <T> T shrink(T counterexample, Consumer<? super T> invariant) {
        T current = counterexample;
        int budget = SHRINK_BUDGET;
        boolean progressed = true;
        while (progressed && budget > 0) {
            progressed = false;
            for (Object candidate : candidatesFor(current)) {
                if (budget-- <= 0) {
                    break;
                }
                if (violationOf(invariant, Property.<T>cast(candidate)) != null) {
                    current = cast(candidate);
                    progressed = true;
                    break;
                }
            }
        }
        return current;
    }

    /**
     * Strictly-smaller shrink candidates: longs/ints toward 0 (jump to 0 first, then halve, then
     * step), lists toward shorter (halves, then each single-element removal). Anything else
     * shrinks to nothing — ADR-0005 accepts that for the domain's record types.
     */
    private static List<Object> candidatesFor(Object value) {
        List<Object> candidates = new ArrayList<>();
        if (value instanceof Long l && l != 0L) {
            addDistinct(candidates, 0L);
            addDistinct(candidates, l / 2);
            addDistinct(candidates, l - Long.signum(l));
        } else if (value instanceof Integer i && i != 0) {
            addDistinct(candidates, 0);
            addDistinct(candidates, i / 2);
            addDistinct(candidates, i - Integer.signum(i));
        } else if (value instanceof List<?> list && !list.isEmpty()) {
            int size = list.size();
            if (size >= 2) {
                candidates.add(Collections.unmodifiableList(new ArrayList<>(list.subList(0, size / 2))));
                candidates.add(Collections.unmodifiableList(new ArrayList<>(list.subList(size / 2, size))));
            }
            for (int i = 0; i < size; i++) {
                List<Object> shorter = new ArrayList<>(list);
                shorter.remove(i);
                candidates.add(Collections.unmodifiableList(shorter));
            }
        }
        return candidates;
    }

    private static void addDistinct(List<Object> candidates, Object candidate) {
        if (!candidates.contains(candidate)) {
            candidates.add(candidate);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(Object candidate) {
        // Sound by construction: candidatesFor only derives values of the same runtime shape
        // (a smaller Long from a Long, a shorter List from a List).
        return (T) candidate;
    }

    private static int iterations() {
        String raw = System.getProperty(ITERATIONS_PROPERTY);
        if (raw == null || raw.isBlank()) {
            return DEFAULT_ITERATIONS;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(raw.trim());
        } catch (NumberFormatException notAnInt) {
            throw new IllegalArgumentException(
                    "-D" + ITERATIONS_PROPERTY + " must be a positive int, got: " + raw, notAnInt);
        }
        if (parsed < 1) {
            throw new IllegalArgumentException("-D" + ITERATIONS_PROPERTY + " must be >= 1, got: " + raw);
        }
        return parsed;
    }

    private static long rootSeed() {
        String raw = System.getProperty(SEED_PROPERTY);
        if (raw == null || raw.isBlank()) {
            // The one place fresh entropy enters the harness; every failure message pins the
            // value drawn here. (Test-support code — the ArchUnit ambient-time/entropy rules
            // govern production classes; determinism here is guaranteed by the seed contract.)
            return new SplittableRandom().nextLong();
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException notALong) {
            // Loud, not lenient: a typo'd seed silently ignored would "replay" a fresh random
            // run — the exact unreproducibility TEST-STRATEGY §1 calls a test defect.
            throw new IllegalArgumentException(
                    "-D" + SEED_PROPERTY + " must be a long, got: " + raw, notALong);
        }
    }
}
