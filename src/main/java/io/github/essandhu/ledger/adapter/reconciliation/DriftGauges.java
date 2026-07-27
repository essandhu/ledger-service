package io.github.essandhu.ledger.adapter.reconciliation;

import java.util.concurrent.atomic.AtomicLong;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * The PLAN §8 drift gauges — {@code ledger.reconciliation.drift.accounts} and
 * {@code .drift.absolute} — as "at last completed run" state: the job listener overwrites both
 * after every CLEAN/DRIFT run; a FAILED run leaves them, because a sweep that produced no
 * verdict has nothing truer to report than the last one that did. The codebase's first gauges,
 * so the shape is deliberate: one long-lived bean strongly holds the two {@link AtomicLong}s
 * (Micrometer gauges only weak-reference their state object and would read NaN after GC) and
 * registers each name exactly once at construction — re-registering an existing name is a
 * silent no-op, so per-run registration would freeze the first run's values forever. Eager
 * construction also means the gauges exist (at 0) from boot, scrapeable before any run.
 */
final class DriftGauges {

    private final AtomicLong driftedAccounts = new AtomicLong();
    private final AtomicLong absoluteDrift = new AtomicLong();

    DriftGauges(MeterRegistry registry) {
        registry.gauge("ledger.reconciliation.drift.accounts", driftedAccounts);
        registry.gauge("ledger.reconciliation.drift.absolute", absoluteDrift);
    }

    /** Overwrites both gauges with a completed run's figures (Σ|delta| in minor units). */
    void record(long driftCount, long absoluteDriftMinorUnits) {
        driftedAccounts.set(driftCount);
        absoluteDrift.set(absoluteDriftMinorUnits);
    }
}
