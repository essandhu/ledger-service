package io.github.essandhu.ledger.adapter.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

import com.jayway.jsonpath.JsonPath;

import io.github.essandhu.ledger.application.port.out.JournalRepository;
import io.github.essandhu.ledger.config.LedgerRealmRoleConverter;
import io.github.essandhu.ledger.domain.model.EntryId;
import io.github.essandhu.ledger.domain.model.EntryType;
import io.github.essandhu.ledger.domain.model.JournalEntry;
import io.github.essandhu.ledger.domain.model.Posting;
import io.github.essandhu.ledger.support.LedgerIntegrationTest;

import static java.util.Map.of;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/**
 * The FOUND half of {@code findByCreatorAndKey} — the mechanism that makes ADR-0004 option
 * 3b's degradation real: after an idempotency record is purged, the entry itself must still
 * answer "this (principal, key) already succeeded", which only works if the lookup reassembles
 * the exact entry the keyed post persisted. The miss half already runs everywhere the purge
 * and degradation suites probe absent keys; this class pins the reassembly. The entry is
 * created through the production write path (the {@code IdempotencyApiIntegrationTest} route)
 * so {@code created_by} is a real JWT subject and the balance/posting invariants hold at rest
 * without any fixture bookkeeping.
 */
@LedgerIntegrationTest
@DisplayName("ADR-0004 3b: findByCreatorAndKey reassembles the entry posted under the key")
class JournalPersistenceAdapterIntegrationTest {

    @Autowired
    private JournalRepository journal;

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    @DisplayName("the entry posted under (subject, key) comes back whole: same id, exactly its legs in leg order, key round-tripped")
    void found_path_reassembles_the_entry_posted_under_the_key() {
        String subject = "journal-adapter-" + UUID.randomUUID();
        String key = "journal-adapter-key-" + UUID.randomUUID();
        String debit = createAccount("ja-debit-" + UUID.randomUUID());
        String credit = createAccount("ja-credit-" + UUID.randomUUID());

        MvcTestResult posted = mvc.post().uri("/api/v1/journal-entries").with(writer(subject))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"description": null, "postings": [
                          {"accountId": "%s", "amount": {"amount": 250, "currency": "EUR"}},
                          {"accountId": "%s", "amount": {"amount": -250, "currency": "EUR"}}]}
                        """.formatted(debit, credit))
                .exchange();
        assertThat(posted).hasStatus(HttpStatus.CREATED);
        UUID entryId = UUID.fromString(JsonPath.read(body(posted), "$.id"));

        JournalEntry entry = transactionTemplate.execute(
                tx -> journal.findByCreatorAndKey(subject, key)).orElseThrow();

        assertThat(entry.id()).isEqualTo(new EntryId(entryId));
        assertThat(entry.createdBy()).isEqualTo(subject);
        assertThat(entry.idempotencyKey())
                .as("the key rides on the entry forever — the permanent double-post guard")
                .isEqualTo(key);
        assertThat(entry.entryType()).isEqualTo(EntryType.JOURNAL);
        // Exactly its two legs, in leg order: the id-ascending read reproduces posting order
        // because sequential UUIDv7 ids are strictly increasing (PostingJpaRepository) — a
        // reassembly that reordered, dropped, or picked up a stray leg fails here.
        assertThat(entry.postings()).extracting(posting -> posting.accountId().value())
                .containsExactly(UUID.fromString(debit), UUID.fromString(credit));
        assertThat(entry.postings()).extracting(posting -> posting.amount().amount())
                .containsExactly(250L, -250L);
        assertThat(entry.postings()).extracting(Posting::postedAt)
                .as("header and legs share the one postedAt read under the lock (PLAN §4.6)")
                .containsExactly(entry.postedAt(), entry.postedAt());

        // The primary-key lookup must answer the IDENTICAL value — same reassembly, whichever
        // index served it (record equality covers every component, postings included).
        Optional<JournalEntry> byId = transactionTemplate.execute(
                tx -> journal.findById(new EntryId(entryId)));
        assertThat(byId).contains(entry);
    }

    private static RequestPostProcessor admin() {
        return jwt().jwt(j -> j.claim("realm_access", of("roles", List.of("LEDGER_ADMIN"))))
                .authorities(new LedgerRealmRoleConverter());
    }

    private static RequestPostProcessor writer(String subject) {
        return jwt().jwt(j -> j.subject(subject)
                        .claim("realm_access", of("roles", List.of("LEDGER_WRITE"))))
                .authorities(new LedgerRealmRoleConverter());
    }

    /** allowNegative, so either leg direction is accepted without funding choreography. */
    private String createAccount(String name) {
        MvcTestResult result = mvc.post().uri("/api/v1/accounts").with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name": "%s", "currency": "EUR", "type": "ASSET", "allowNegative": true}
                        """.formatted(name))
                .exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return JsonPath.read(body(result), "$.id");
    }

    private static String body(MvcTestResult result) {
        try {
            return result.getResponse().getContentAsString();
        } catch (java.io.UnsupportedEncodingException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
