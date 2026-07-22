package io.github.essandhu.ledger.support.fakes;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

import io.github.essandhu.ledger.application.port.out.IdGenerator;

/** Deterministic {@link IdGenerator}: hands out the given ids in order, fails when exhausted. */
public final class FixedIdGenerator implements IdGenerator {

    private final Deque<UUID> remaining;

    public FixedIdGenerator(UUID... ids) {
        this.remaining = new ArrayDeque<>(List.of(ids));
    }

    @Override
    public UUID nextId() {
        UUID next = remaining.poll();
        if (next == null) {
            throw new IllegalStateException("FixedIdGenerator exhausted");
        }
        return next;
    }
}
