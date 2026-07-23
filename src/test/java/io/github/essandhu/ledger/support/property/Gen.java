package io.github.essandhu.ledger.support.property;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.SplittableRandom;
import java.util.function.Function;

/**
 * A composable generator of pseudo-random values — the foundation of the in-repo property
 * harness (ADR-0005). The harness exists in this repository, rather than as a dependency,
 * because the standard JVM library (jqwik) is a JUnit Platform 1.x engine with no Platform-6
 * release for Boot 4.1's JUnit 6, its own notes call Platform-6 support "if ever realised", and
 * its 1.10+ license discourages use with AI coding agents — which this openly AI-assisted
 * project respects. The ledger's generated values are simple (longs, currencies, small object
 * graphs), so this ~10% of jqwik's surface is all the property suites need; if a compatible
 * successor appears, {@code Gen<T>} maps mechanically onto {@code Arbitrary<T>} (ADR-0005's
 * superseding-ADR clause).
 *
 * <p><b>Determinism contract</b> (TEST-STRATEGY §1: "a failure message that cannot be reproduced
 * is treated as a test defect"): a generator must be a pure function of the
 * {@link SplittableRandom} handed to it — no mutable state, no ambient entropy, no ambient time.
 * {@link Property#check} derives one {@code SplittableRandom} per iteration from a root seed via
 * {@link SplittableRandom#split()}, so purity here is exactly what makes
 * {@code -Dledger.property.seed} replay a failure bit-for-bit.
 *
 * <p>Combinators draw from the same {@code SplittableRandom} in sequence ({@code flatMap} runs
 * the derived generator on the rng that produced its input), which keeps the whole composed
 * generator a deterministic function of the iteration seed.
 */
@FunctionalInterface
public interface Gen<T> {

    /** Produces one value from {@code rng} — pure: same rng state, same value. */
    T generate(SplittableRandom rng);

    /** A generator of {@code f} applied to this generator's values. */
    default <R> Gen<R> map(Function<? super T, ? extends R> f) {
        Objects.requireNonNull(f, "f");
        return rng -> f.apply(generate(rng));
    }

    /**
     * Dependent generation: draws a value, then runs the generator {@code f} derives from it on
     * the same rng. This is how size- or content-dependent structures are built (e.g. "pick an
     * index into the leg list just generated").
     */
    default <R> Gen<R> flatMap(Function<? super T, Gen<R>> f) {
        Objects.requireNonNull(f, "f");
        return rng -> f.apply(generate(rng)).generate(rng);
    }

    /**
     * A generator of immutable lists of this generator's values, sized uniformly in
     * {@code [min, max]} (both inclusive).
     */
    default Gen<List<T>> listOf(int min, int max) {
        if (min < 0 || max < min) {
            throw new IllegalArgumentException(
                    "listOf bounds must satisfy 0 <= min <= max, got [%d, %d]".formatted(min, max));
        }
        return rng -> {
            int size = min == max ? min : rng.nextInt(min, max + 1);
            List<T> values = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                values.add(generate(rng));
            }
            return List.copyOf(values);
        };
    }

    /** The degenerate generator: always {@code value} (which may be null — e.g. an absent description). */
    static <T> Gen<T> constant(T value) {
        return rng -> value;
    }

    /**
     * Uniform longs in {@code [minInclusive, maxInclusive]} — inclusive on BOTH ends, unlike
     * {@link SplittableRandom#nextLong(long, long)}, because the property suites must be able to
     * generate {@code Long.MAX_VALUE} itself (the overflow band of ADR-0001's proof list), which
     * an exclusive upper bound cannot express.
     */
    static Gen<Long> longs(long minInclusive, long maxInclusive) {
        if (minInclusive > maxInclusive) {
            throw new IllegalArgumentException(
                    "longs bounds must satisfy min <= max, got [%d, %d]".formatted(minInclusive, maxInclusive));
        }
        if (minInclusive == maxInclusive) {
            return constant(minInclusive);
        }
        if (minInclusive == Long.MIN_VALUE && maxInclusive == Long.MAX_VALUE) {
            return SplittableRandom::nextLong;
        }
        if (maxInclusive == Long.MAX_VALUE) {
            // Shift the window down one to fit the exclusive-bound API, then shift the draw back.
            // min > Long.MIN_VALUE here (the full-range case is handled above), so min - 1 is safe.
            return rng -> rng.nextLong(minInclusive - 1, maxInclusive) + 1;
        }
        return rng -> rng.nextLong(minInclusive, maxInclusive + 1);
    }

    /** Uniform ints in {@code [minInclusive, maxInclusive]}, both ends inclusive (see {@link #longs}). */
    static Gen<Integer> ints(int minInclusive, int maxInclusive) {
        if (minInclusive > maxInclusive) {
            throw new IllegalArgumentException(
                    "ints bounds must satisfy min <= max, got [%d, %d]".formatted(minInclusive, maxInclusive));
        }
        if (minInclusive == maxInclusive) {
            return constant(minInclusive);
        }
        if (minInclusive == Integer.MIN_VALUE && maxInclusive == Integer.MAX_VALUE) {
            return SplittableRandom::nextInt;
        }
        if (maxInclusive == Integer.MAX_VALUE) {
            return rng -> rng.nextInt(minInclusive - 1, maxInclusive) + 1;
        }
        return rng -> rng.nextInt(minInclusive, maxInclusive + 1);
    }

    /** Picks one of the given generators uniformly, then draws from it. */
    @SafeVarargs
    static <T> Gen<T> oneOf(Gen<? extends T>... choices) {
        if (choices.length == 0) {
            throw new IllegalArgumentException("oneOf needs at least one generator");
        }
        List<Gen<? extends T>> alternatives = List.of(choices);
        return rng -> alternatives.get(rng.nextInt(alternatives.size())).generate(rng);
    }

    /**
     * Picks one of the given generators with probability proportional to its weight, then draws
     * from it. This is how the domain generators keep rare-but-load-bearing regions (the
     * near-{@code Long.MAX_VALUE} band, exponent-0/3 currencies) present in every run without
     * drowning out the everyday cases.
     */
    @SafeVarargs
    static <T> Gen<T> frequency(Weighted<T>... choices) {
        if (choices.length == 0) {
            throw new IllegalArgumentException("frequency needs at least one weighted generator");
        }
        List<Weighted<T>> alternatives = List.of(choices);
        int total = 0;
        for (Weighted<T> choice : alternatives) {
            total = Math.addExact(total, choice.weight());
        }
        int totalWeight = total;
        return rng -> {
            int roll = rng.nextInt(totalWeight);
            for (Weighted<T> choice : alternatives) {
                roll -= choice.weight();
                if (roll < 0) {
                    return choice.gen().generate(rng);
                }
            }
            // Unreachable: roll < totalWeight and the weights sum to totalWeight exactly.
            throw new AssertionError("frequency roll exceeded total weight " + totalWeight);
        };
    }

    /** One {@link #frequency} alternative: {@code gen}, drawn with relative probability {@code weight}. */
    record Weighted<T>(int weight, Gen<? extends T> gen) {
        public Weighted {
            if (weight < 1) {
                throw new IllegalArgumentException("weight must be >= 1, got " + weight);
            }
            Objects.requireNonNull(gen, "gen");
        }
    }
}
