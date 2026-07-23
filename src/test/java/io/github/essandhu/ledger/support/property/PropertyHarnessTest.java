package io.github.essandhu.ledger.support.property;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Meta-tests for the in-repo property harness — the harness proving itself. TEST-STRATEGY §2.2
 * and ADR-0005 both require these to land BEFORE any invariant depends on {@link Property}: the
 * failure mode ADR-0005 names for an owned harness is "harness bugs masking real defects", and
 * the only defense is that the harness's own guarantees (a false property fails, a failure is
 * replayable, shrinking terminates, iteration counts obey their overrides) are themselves
 * test-backed. TEST-STRATEGY §1's determinism ground rule is the sharpest of these: "a failure
 * message that cannot be reproduced is treated as a test defect" — so seed replay is asserted
 * here by literally parsing a failure message and re-running from it.
 *
 * <p>These tests deliberately know the failure-message format (seed marker, counterexample and
 * shrunk-to lines). That coupling is the point: the format IS the replay contract a human (or
 * CI log reader) depends on, so a reformat that breaks parseability must break this class.
 */
@Tag("property")
@DisplayName("ADR-0005: property harness meta-tests — replayable seeds, terminating shrinker, honored iteration counts")
class PropertyHarnessTest {

    private static final Pattern SEED = Pattern.compile("-Dledger\\.property\\.seed=(-?\\d+)");
    private static final Pattern COUNTEREXAMPLE = Pattern.compile("counterexample: ([^\\r\\n]*)");
    private static final Pattern SHRUNK = Pattern.compile("shrunk to: ([^\\r\\n]*)");

    private String ambientSeed;
    private String ambientIterations;

    @BeforeEach
    void isolate_from_ambient_overrides() {
        // CI may legitimately set either property JVM-wide (raised iterations, a replay session).
        // The meta-tests must observe the harness's own defaults, so the ambient values are
        // snapshotted and cleared here — and restored below, because clearing them for the whole
        // JVM would silently change what every later property class runs with.
        ambientSeed = System.getProperty(Property.SEED_PROPERTY);
        ambientIterations = System.getProperty(Property.ITERATIONS_PROPERTY);
        System.clearProperty(Property.SEED_PROPERTY);
        System.clearProperty(Property.ITERATIONS_PROPERTY);
    }

    @AfterEach
    void restore_ambient_overrides() {
        restore(Property.SEED_PROPERTY, ambientSeed);
        restore(Property.ITERATIONS_PROPERTY, ambientIterations);
    }

    @Test
    @DisplayName("a known-false property fails, and the message carries the iteration and a replayable root seed")
    void known_false_property_fails_with_replayable_seed() {
        Throwable failure = catchThrowable(() ->
                Property.check(Gen.longs(0, 1_000_000_000L), value -> assertThat(value).isNegative()));

        assertThat(failure)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("property falsified at iteration ")
                .hasMessageContaining("-Dledger.property.seed=");
        // The seed must be extractable and parseable — a seed a human cannot copy-paste back
        // into -Dledger.property.seed is not replayable, whatever the message claims.
        seedFrom(failure.getMessage());
    }

    @Test
    @DisplayName("replaying the reported seed via -Dledger.property.seed reproduces the identical counterexample")
    void replaying_the_seed_reproduces_the_identical_counterexample() {
        // The range is astronomically wide, so two runs agreeing on the counterexample cannot be
        // luck — agreement is only possible if the seed actually steers generation (split
        // discipline: per-iteration SplittableRandoms derive from the root deterministically).
        Gen<Long> gen = Gen.longs(1, Long.MAX_VALUE - 1);

        Throwable first = catchThrowable(() -> Property.check(gen, value -> assertThat(value).isNegative()));
        assertThat(first).isInstanceOf(AssertionError.class);
        long seed = seedFrom(first.getMessage());
        String original = counterexampleFrom(first.getMessage());

        System.setProperty(Property.SEED_PROPERTY, Long.toString(seed));
        Throwable replay = catchThrowable(() -> Property.check(gen, value -> assertThat(value).isNegative()));

        assertThat(replay).isInstanceOf(AssertionError.class);
        assertThat(counterexampleFrom(replay.getMessage()))
                .as("the seeded run must regenerate the exact counterexample of the original run")
                .isEqualTo(original);
        assertThat(seedFrom(replay.getMessage()))
                .as("the replayed failure must report the same root seed it ran under")
                .isEqualTo(seed);
    }

    @Test
    @DisplayName("shrinking terminates, walking a numeric counterexample down to the minimal failing value 0")
    void shrinking_walks_numbers_toward_zero() {
        // Every generated value fails, so the minimal failing long is 0. Reaching it proves both
        // termination (this call returning at all — the shrinker is budget-bounded and every
        // accepted candidate is strictly smaller) and direction (numbers shrink toward zero).
        Throwable failure = catchThrowable(() ->
                Property.check(Gen.longs(0, 1_000_000_000L), value -> assertThat(value).isNegative()));

        assertThat(failure).isInstanceOf(AssertionError.class);
        assertThat(shrunkFrom(failure.getMessage())).isEqualTo("0");
    }

    @Test
    @DisplayName("shrinking terminates, walking a list counterexample down to a single element")
    void shrinking_walks_lists_toward_shorter() {
        // "is empty" passes on the empty list, so the minimal failing list has exactly one
        // element — the shrinker must stop there, not loop and not overshoot.
        Throwable failure = catchThrowable(() ->
                Property.check(Gen.longs(0, 9).listOf(3, 8), list -> assertThat(list).isEmpty()));

        assertThat(failure).isInstanceOf(AssertionError.class);
        assertThat(shrunkFrom(failure.getMessage())).matches("\\[\\d\\]");
    }

    @Test
    @DisplayName("a known-true property passes, running exactly the default 200 iterations")
    void known_true_property_passes_with_default_iterations() {
        AtomicInteger runs = new AtomicInteger();

        Property.check(Gen.longs(-1_000, 1_000), value -> {
            runs.incrementAndGet();
            assertThat(value).isBetween(-1_000L, 1_000L);
        });

        assertThat(runs).hasValue(Property.DEFAULT_ITERATIONS);
    }

    @Test
    @DisplayName("-Dledger.property.iterations overrides the iteration count exactly")
    void iteration_count_override_is_honored() {
        System.setProperty(Property.ITERATIONS_PROPERTY, "17");
        AtomicInteger runs = new AtomicInteger();

        Property.check(Gen.longs(0, 10), value -> runs.incrementAndGet());

        assertThat(runs).hasValue(17);
    }

    @Test
    @DisplayName("generator variety: oneOf and frequency stay inside their alternatives, listOf inside its bounds")
    void combinators_respect_their_contracts() {
        // Not a statistical test (ADR-0005 explicitly forgoes statistics) — just the hard
        // membership/bounds half of the combinator contracts, property-checked like anything else.
        Property.check(Gen.oneOf(Gen.constant("a"), Gen.constant("b")),
                value -> assertThat(value).isIn("a", "b"));
        Property.check(
                Gen.frequency(
                        new Gen.Weighted<>(1, Gen.constant(1L)),
                        new Gen.Weighted<>(3, Gen.longs(10, 20))),
                value -> assertThat(value == 1L || (value >= 10L && value <= 20L)).isTrue());
        Property.check(Gen.longs(0, 9).listOf(2, 5), list -> {
            assertThat(list).hasSizeBetween(2, 5);
            assertThat(list).allSatisfy(element -> assertThat(element).isBetween(0L, 9L));
        });
    }

    private static long seedFrom(String message) {
        return Long.parseLong(group(SEED, message,
                "failure message must carry a copy-pasteable -Dledger.property.seed=<long>"));
    }

    private static String counterexampleFrom(String message) {
        return group(COUNTEREXAMPLE, message, "failure message must carry a 'counterexample:' line");
    }

    private static String shrunkFrom(String message) {
        return group(SHRUNK, message, "failure message must carry a 'shrunk to:' line");
    }

    private static String group(Pattern pattern, String message, String requirement) {
        Matcher matcher = pattern.matcher(message);
        assertThat(matcher.find()).as("%s — was:%n%s", requirement, message).isTrue();
        return matcher.group(1);
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
