package io.github.essandhu.ledger.support.property;

/**
 * Iteration-count override for property suites that round-trip the database per iteration
 * (ADR-0005's default of 200 is right for pure-domain properties, hostile for those). An
 * explicit {@code -Dledger.property.iterations} always wins — the knob must keep meaning what
 * it says — so only the DEFAULT is reduced, and the previous state is restored in a finally
 * so pure-domain property suites sharing the JVM keep their 200.
 */
public final class Iterations {

    private Iterations() {
    }

    public static void withReducedDefault(String iterations, Runnable check) {
        if (System.getProperty(Property.ITERATIONS_PROPERTY) != null) {
            check.run();
            return;
        }
        System.setProperty(Property.ITERATIONS_PROPERTY, iterations);
        try {
            check.run();
        } finally {
            System.clearProperty(Property.ITERATIONS_PROPERTY);
        }
    }
}
