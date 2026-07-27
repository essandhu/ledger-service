package io.github.essandhu.ledger.application.port.in;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import io.github.essandhu.ledger.application.port.in.PostJournalEntryUseCase.PostEntryCommand;
import io.github.essandhu.ledger.application.port.in.ReverseEntryUseCase.ReverseCommand;
import io.github.essandhu.ledger.application.port.in.TransferFundsUseCase.TransferCommand;
import io.github.essandhu.ledger.domain.model.EntryDraft;
import io.github.essandhu.ledger.domain.model.Money;

/**
 * ADR-0004's canonical command form and its SHA-256 — hashing what the service will ACT ON,
 * not the raw request bytes, so a retry serialized differently by a different HTTP client
 * (reordered JSON fields, whitespace, {@code 1e2}) can never false-conflict (option 2c).
 *
 * <p><strong>The canonical form is a FROZEN persisted contract</strong> (pinned by the
 * golden-file test): stored hashes must stay comparable forever, so any change here can turn
 * old replays into false conflicts and needs explicit migration reasoning. Hand-rolled on the
 * JDK deliberately, not delegated to Jackson: the I14 rule keeps frameworks out of the
 * application core, and a serialization library's defaults drifting across upgrades is exactly
 * the instability a frozen form cannot afford. The rules, per ADR-0004: JSON, fields in the
 * command type's declared order behind a leading {@code "command"} type discriminator (so the
 * three command types can never hash-collide by shape), no insignificant whitespace, amounts
 * as plain integers in minor units (ADR-0001), currency codes uppercase, UUIDs lowercase
 * (as {@code UUID.toString} emits), legs in request order (reordered legs are a DIFFERENT
 * command — a false conflict is cheaper than a wrong equivalence guess), strings escaped to
 * PURE ASCII ({@code \"} and {@code \\}; every other char outside printable ASCII
 * [0x20..0x7e] as {@code \}{@code uxxxx} lowercase hex, one escape per UTF-16 unit), and
 * absent/null description as literal {@code null} (an absent reversal body and an explicit
 * null description are the same command). ASCII-only escaping is load-bearing, not cosmetic:
 * it makes the byte encoding injective on char sequences — raw UTF-8 would collapse a lone
 * surrogate (reachable via a JSON {@code \}{@code uD800} escape) into the replacement byte,
 * letting two DIFFERENT commands hash identically and a tampered payload replay instead of
 * conflict. The idempotency key itself, the principal, and all transport headers are
 * excluded: the hash identifies the OPERATION — scope comes from the (principal, key) lookup.
 */
public final class CanonicalCommand {

    private CanonicalCommand() {
    }

    /** Convenience: the stored/compared {@code request_hash} of a command's canonical form. */
    public static String hash(PostEntryCommand command) {
        return sha256Hex(canonicalJson(command));
    }

    public static String hash(TransferCommand command) {
        return sha256Hex(canonicalJson(command));
    }

    public static String hash(ReverseCommand command) {
        return sha256Hex(canonicalJson(command));
    }

    public static String canonicalJson(PostEntryCommand command) {
        StringBuilder json = new StringBuilder(64 + 96 * command.legs().size());
        json.append("{\"command\":\"post-journal-entry\",\"description\":");
        appendString(json, command.description());
        json.append(",\"legs\":[");
        boolean first = true;
        for (EntryDraft.Leg leg : command.legs()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append("{\"accountId\":\"").append(leg.accountId().value())
                    .append("\",\"amount\":");
            appendMoney(json, leg.amount());
            json.append('}');
        }
        return json.append("]}").toString();
    }

    public static String canonicalJson(TransferCommand command) {
        StringBuilder json = new StringBuilder(192);
        json.append("{\"command\":\"transfer-funds\",\"source\":\"")
                .append(command.source().value())
                .append("\",\"target\":\"").append(command.target().value())
                .append("\",\"amount\":");
        appendMoney(json, command.amount());
        json.append(",\"description\":");
        appendString(json, command.description());
        return json.append('}').toString();
    }

    public static String canonicalJson(ReverseCommand command) {
        StringBuilder json = new StringBuilder(96);
        json.append("{\"command\":\"reverse-entry\",\"originalId\":\"")
                .append(command.originalId().value())
                .append("\",\"description\":");
        appendString(json, command.description());
        return json.append('}').toString();
    }

    /** Lowercase-hex SHA-256 over the canonical form's UTF-8 bytes — the 64-char
     * {@code request_hash} of the schema. */
    public static String sha256Hex(String canonicalJson) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            // Every conformant JRE ships SHA-256 (it is required by the Java security spec).
            throw new IllegalStateException("JRE lacks mandatory SHA-256", impossible);
        }
        return HexFormat.of().formatHex(
                digest.digest(canonicalJson.getBytes(StandardCharsets.UTF_8)));
    }

    private static void appendMoney(StringBuilder json, Money money) {
        json.append("{\"amount\":").append(money.amount())
                .append(",\"currency\":\"").append(money.currency().value()).append("\"}");
    }

    private static void appendString(StringBuilder json, String value) {
        if (value == null) {
            json.append("null");
            return;
        }
        json.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"') {
                json.append("\\\"");
            } else if (c == '\\') {
                json.append("\\\\");
            } else if (c < 0x20 || c > 0x7e) {
                // One escape per UTF-16 unit — surrogates included, so the form stays
                // injective even for lone surrogates (see class javadoc).
                json.append("\\u").append(HexFormat.of().toHexDigits((short) c));
            } else {
                json.append(c);
            }
        }
        json.append('"');
    }
}
