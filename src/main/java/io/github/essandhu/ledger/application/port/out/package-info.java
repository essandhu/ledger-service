/**
 * Driven ports: repository/store interfaces the application layer needs
 * (accounts, journal, balances incl. the ordered-locking contract of ADR-0003, idempotency
 * records, reconciliation runs). Implemented in {@code adapter.persistence}.
 */
package io.github.essandhu.ledger.application.port.out;
