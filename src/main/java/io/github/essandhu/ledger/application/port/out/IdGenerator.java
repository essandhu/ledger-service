package io.github.essandhu.ledger.application.port.out;

import java.util.UUID;

/**
 * The single source of identifiers: UUIDv7, generated application-side, behind a
 * port so the core stays framework-free and tests control identity. Implemented in config
 * wiring over java-uuid-generator.
 */
@FunctionalInterface
public interface IdGenerator {

    UUID nextId();
}
