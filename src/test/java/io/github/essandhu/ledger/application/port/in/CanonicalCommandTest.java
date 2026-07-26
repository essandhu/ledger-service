package io.github.essandhu.ledger.application.port.in;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.github.essandhu.ledger.application.port.in.PostJournalEntryUseCase.PostEntryCommand;
import io.github.essandhu.ledger.application.port.in.ReverseEntryUseCase.ReverseCommand;
import io.github.essandhu.ledger.application.port.in.TransferFundsUseCase.TransferCommand;
import io.github.essandhu.ledger.domain.model.AccountId;
import io.github.essandhu.ledger.domain.model.CurrencyCode;
import io.github.essandhu.ledger.domain.model.EntryDraft;
import io.github.essandhu.ledger.domain.model.EntryId;
import io.github.essandhu.ledger.domain.model.Money;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * THE GOLDEN FILE of ADR-0004's frozen canonical form. The literal strings below are the
 * persisted contract: stored request hashes compare against hashes of exactly these bytes, so
 * a failing assertion here means old replays would become false conflicts — do not "fix" the
 * expectation; any change to the canonical form needs explicit migration reasoning (ADR-0004
 * §Consequences). The hash function itself is pinned separately against the published NIST
 * SHA-256 vectors, so the two halves of {@code request_hash} are each frozen independently.
 */
@DisplayName("ADR-0004: the frozen canonical command form and its SHA-256 (golden file)")
class CanonicalCommandTest {

    private static final CurrencyCode EUR = new CurrencyCode("EUR");
    private static final AccountId A =
            new AccountId(UUID.fromString("019817b4-0000-7000-8000-00000000000a"));
    private static final AccountId B =
            new AccountId(UUID.fromString("019817b4-0000-7000-8000-00000000000b"));
    private static final EntryId E1 =
            new EntryId(UUID.fromString("019817b4-0000-7000-8000-0000000000e1"));

    private static PostEntryCommand journal(String description, String createdBy, String key,
            long first) {
        return new PostEntryCommand(description, List.of(
                new EntryDraft.Leg(A, Money.of(first, EUR)),
                new EntryDraft.Leg(B, Money.of(-first, EUR))),
                createdBy, key);
    }

    @Nested
    @DisplayName("the frozen forms, byte for byte")
    class FrozenForms {

        @Test
        @DisplayName("post-journal-entry: discriminator, declared field order, nested Money, pure-ASCII escaping")
        void journal_entry_canonical_form_is_frozen() {
            // Description exercises every escaping rule at once: quote and backslash escaped,
            // every char outside printable ASCII (control AND non-ASCII alike) as lowercase
            // backslash-u four-hex — the canonical bytes are pure ASCII.
            PostEntryCommand command =
                    journal("july \"rent\" c:\\books\nétage", "golden-tester", "golden-key", 1099);
            assertThat(CanonicalCommand.canonicalJson(command)).isEqualTo(
                    "{\"command\":\"post-journal-entry\","
                    + "\"description\":\"july \\\"rent\\\" c:\\\\books\\u000a\\u00e9tage\","
                    + "\"legs\":["
                    + "{\"accountId\":\"019817b4-0000-7000-8000-00000000000a\","
                    + "\"amount\":{\"amount\":1099,\"currency\":\"EUR\"}},"
                    + "{\"accountId\":\"019817b4-0000-7000-8000-00000000000b\","
                    + "\"amount\":{\"amount\":-1099,\"currency\":\"EUR\"}}"
                    + "]}");
        }

        @Test
        @DisplayName("escaping is injective: a lone surrogate never collapses into the bytes of another description")
        void lone_surrogates_do_not_collide() {
            // Raw UTF-8 would encode an unpaired surrogate (reachable via a JSON \uD800
            // escape in the request body) as the replacement byte '?', making it hash-equal
            // to a literal "?" — a DIFFERENT payload replaying instead of conflicting. The
            // pure-ASCII form escapes each UTF-16 unit, so the two stay distinct.
            PostEntryCommand surrogate = journal("\uD800", "alice", "k", 100);
            PostEntryCommand questionMark = journal("?", "alice", "k", 100);
            assertThat(CanonicalCommand.canonicalJson(surrogate)).contains("\\ud800");
            assertThat(CanonicalCommand.hash(surrogate))
                    .isNotEqualTo(CanonicalCommand.hash(questionMark));
        }

        @Test
        @DisplayName("transfer-funds: source, target, Money, null description as literal null")
        void transfer_canonical_form_is_frozen() {
            TransferCommand command = new TransferCommand(A, B, Money.of(250, EUR), null,
                    "golden-tester", "golden-key");
            assertThat(CanonicalCommand.canonicalJson(command)).isEqualTo(
                    "{\"command\":\"transfer-funds\","
                    + "\"source\":\"019817b4-0000-7000-8000-00000000000a\","
                    + "\"target\":\"019817b4-0000-7000-8000-00000000000b\","
                    + "\"amount\":{\"amount\":250,\"currency\":\"EUR\"},"
                    + "\"description\":null}");
        }

        @Test
        @DisplayName("reverse-entry: original id and the reversal's own description")
        void reversal_canonical_form_is_frozen() {
            ReverseCommand command = new ReverseCommand(E1, null, "golden-tester", "golden-key");
            assertThat(CanonicalCommand.canonicalJson(command)).isEqualTo(
                    "{\"command\":\"reverse-entry\","
                    + "\"originalId\":\"019817b4-0000-7000-8000-0000000000e1\","
                    + "\"description\":null}");
        }

        @Test
        @DisplayName("sha256Hex is real SHA-256, lowercase hex — the published NIST vectors")
        void sha256_matches_the_published_vectors() {
            assertThat(CanonicalCommand.sha256Hex("abc")).isEqualTo(
                    "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
            assertThat(CanonicalCommand.sha256Hex("")).isEqualTo(
                    "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        }

        @Test
        @DisplayName("hash(command) IS sha256Hex over the canonical form — no third ingredient")
        void hash_is_sha256_of_the_canonical_form() {
            PostEntryCommand command = journal(null, "golden-tester", "golden-key", 100);
            assertThat(CanonicalCommand.hash(command))
                    .isEqualTo(CanonicalCommand.sha256Hex(CanonicalCommand.canonicalJson(command)))
                    .hasSize(64)
                    .matches("[0-9a-f]{64}");
        }
    }

    @Nested
    @DisplayName("what the hash sees — and what it deliberately does not")
    class HashScope {

        @Test
        @DisplayName("the key and the principal are EXCLUDED: scope comes from the lookup, not the hash")
        void key_and_principal_do_not_affect_the_hash() {
            assertThat(CanonicalCommand.hash(journal("d", "alice", "key-1", 100)))
                    .isEqualTo(CanonicalCommand.hash(journal("d", "bob", "key-2", 100)));
        }

        @Test
        @DisplayName("every payload field is INCLUDED: amount, description, and leg order each change the hash")
        void payload_changes_change_the_hash() {
            String baseline = CanonicalCommand.hash(journal("d", "alice", "k", 100));
            assertThat(CanonicalCommand.hash(journal("d", "alice", "k", 101)))
                    .as("amount").isNotEqualTo(baseline);
            assertThat(CanonicalCommand.hash(journal("tampered", "alice", "k", 100)))
                    .as("description").isNotEqualTo(baseline);
            assertThat(CanonicalCommand.hash(journal(null, "alice", "k", 100)))
                    .as("null vs present description").isNotEqualTo(baseline);
            PostEntryCommand swapped = new PostEntryCommand("d", List.of(
                    new EntryDraft.Leg(B, Money.of(-100, EUR)),
                    new EntryDraft.Leg(A, Money.of(100, EUR))),
                    "alice", "k");
            assertThat(CanonicalCommand.hash(swapped))
                    .as("leg order is semantically significant (ADR-0004: no equivalence guessing)")
                    .isNotEqualTo(baseline);
        }

        @Test
        @DisplayName("ADR-0004 option 1b: a transfer and its expanded journal form hash differently — cross-endpoint retry conflicts, never double-posts")
        void transfer_and_equivalent_journal_hash_differently() {
            TransferCommand transfer = new TransferCommand(A, B, Money.of(100, EUR), null,
                    "alice", "k");
            PostEntryCommand expanded = journal(null, "alice", "k", 100);
            assertThat(CanonicalCommand.hash(transfer))
                    .isNotEqualTo(CanonicalCommand.hash(expanded));
        }
    }
}
