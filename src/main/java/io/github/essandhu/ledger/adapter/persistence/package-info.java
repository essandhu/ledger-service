/**
 * Driven adapter: JPA entities ({@code @Immutable} for entries/postings), Spring Data
 * repositories, out-port implementations, and the canonical-order {@code SELECT … FOR UPDATE}
 * locking queries (ADR-0003). The only package allowed to see {@code jakarta.persistence} /
 * {@code org.springframework.data} (ArchUnit-enforced).
 */
package io.github.essandhu.ledger.adapter.persistence;
