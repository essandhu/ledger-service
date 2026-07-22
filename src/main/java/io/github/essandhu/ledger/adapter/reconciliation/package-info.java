/**
 * Driving adapter: the Spring Batch reconciliation job (chunked over accounts, recomputes
 * balances from postings, writes findings, publishes drift gauges — ADR-0002, invariant I15),
 * its schedule, and job/step listeners.
 */
package io.github.essandhu.ledger.adapter.reconciliation;
