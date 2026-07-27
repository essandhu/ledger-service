package io.github.essandhu.ledger.adapter.web;

import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

import io.github.essandhu.ledger.application.port.out.WriteResponseRenderer;
import io.github.essandhu.ledger.domain.model.JournalEntry;

/**
 * Renders the success response the idempotency record stores (ADR-0004: "the stored original
 * response body") — with the SAME auto-configured {@link JsonMapper} MVC serializes responses
 * with, so the stored body is byte-identical to what the first caller received, and a replay
 * returns those bytes verbatim. A driven-port implementation living in the web adapter on
 * purpose: what "the response" looks like is a web concern, and this is the one place that
 * knows it; the application core just asks for "exactly what the client saw". The 201 is
 * {@code PostingResponses}' fresh-post status, recorded as audit data (replays answer 200 per
 * the API contract).
 */
@Component
class StoredWriteResponseRenderer implements WriteResponseRenderer {

    private final JsonMapper mapper;

    StoredWriteResponseRenderer(JsonMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Rendered render(JournalEntry entry) {
        return new Rendered(201, mapper.writeValueAsString(EntryResponse.from(entry)));
    }
}
