/**
 * The framework-free domain core: {@code Money}, {@code Account}, {@code JournalEntry},
 * {@code Posting}, identifiers, and the invariants they enforce (zero-sum entries, overdraft
 * policy, lifecycle rules). Depends on the JDK only — enforced by ArchUnit (invariant I14).
 */
package io.github.essandhu.ledger.domain.model;
