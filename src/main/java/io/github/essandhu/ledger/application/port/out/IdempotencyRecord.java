package io.github.essandhu.ledger.application.port.out;

import java.time.Instant;
import java.util.Objects;

import io.github.essandhu.ledger.domain.model.EntryId;

/**
 * One successful keyed write (ADR-0004, PLAN §4.3): the (principal, key) scope, the SHA-256 of
 * the canonical command it recorded, the entry it produced, and the original response for
 * byte-identical replays. Written exactly once, in the same transaction as the entry; only
 * successful outcomes are recorded (a rejected posting writes nothing, so its retry
 * re-executes). {@code expiresAt} exists from birth so the designed-but-disabled purge is a
 * configuration change, not a migration; V3's permanent backstop index on the entry itself is
 * what survives any purge.
 */
public record IdempotencyRecord(
        String createdBy,
        String idempotencyKey,
        String requestHash,
        EntryId entryId,
        int responseStatus,
        String responseBody,
        Instant createdAt,
        Instant expiresAt) {

    public IdempotencyRecord {
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(requestHash, "requestHash");
        Objects.requireNonNull(entryId, "entryId");
        Objects.requireNonNull(responseBody, "responseBody");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }
}
