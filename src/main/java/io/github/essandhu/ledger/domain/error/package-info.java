/**
 * Domain rule violations (unbalanced entry, too few or zero-amount postings, amount overflow,
 * overdraft, frozen/closed account, currency mismatch, unknown posting account, double
 * reversal, nonzero balance at close). Thrown by the domain, translated to RFC 9457 problem
 * responses in {@code adapter.web}.
 */
package io.github.essandhu.ledger.domain.error;
