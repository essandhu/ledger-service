package io.github.essandhu.ledger.domain.model;

/**
 * Lifecycle states: {@code ACTIVE ⇄ FROZEN}, {@code ACTIVE|FROZEN → CLOSED},
 * CLOSED terminal. The legality of edges lives in {@link Account#transitionTo}, the single
 * home of the state machine.
 */
public enum AccountStatus {
    ACTIVE,
    FROZEN,
    CLOSED
}
