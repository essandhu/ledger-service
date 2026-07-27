package io.github.essandhu.ledger.console.api;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

import io.github.essandhu.ledger.console.api.LedgerApi.AccountPage;
import io.github.essandhu.ledger.console.api.LedgerApi.AccountStatus;
import io.github.essandhu.ledger.console.api.LedgerApi.AccountType;
import io.github.essandhu.ledger.console.api.LedgerApi.Balance;
import io.github.essandhu.ledger.console.api.LedgerApi.Entry;
import io.github.essandhu.ledger.console.api.LedgerApi.StatementPage;

/**
 * The console's one road to the ledger: every call rides the user's own token via the OAuth2
 * relay configured in {@code ApiClientConfig} — the console holds no credentials of its own
 * for the API, so the API's role matrix is the console's role matrix. 4xx/5xx surface as
 * {@code RestClientResponseException} and are rendered by {@code ConsoleErrorAdvice}; this
 * class adds no error translation of its own.
 *
 * <p>Cursor strings are passed back verbatim — the core's codec is strict-canonical and any
 * re-encoding (padding, normalization) turns a valid cursor into a 400.
 */
@Component
public class LedgerApiClient {

    private final RestClient client;

    LedgerApiClient(RestClient.Builder ledgerApiBuilder) {
        this.client = ledgerApiBuilder.build();
    }

    public AccountPage accounts(
            Optional<AccountType> type, Optional<AccountStatus> status, int page, int size) {
        return client.get()
                .uri(builder -> {
                    UriBuilder uri = builder.path("/api/v1/accounts")
                            .queryParam("page", page)
                            .queryParam("size", size);
                    type.ifPresent(t -> uri.queryParam("type", t.name()));
                    status.ifPresent(s -> uri.queryParam("status", s.name()));
                    return uri.build();
                })
                .retrieve()
                .body(AccountPage.class);
    }

    public LedgerApi.Account account(UUID id) {
        return client.get()
                .uri("/api/v1/accounts/{id}", id)
                .retrieve()
                .body(LedgerApi.Account.class);
    }

    /** Live snapshot when {@code at} is empty; as-of balance otherwise. */
    public Balance balance(UUID id, Optional<Instant> at) {
        return client.get()
                .uri(builder -> {
                    UriBuilder uri = builder.path("/api/v1/accounts/{id}/balance");
                    // As a URI VARIABLE the value is strictly encoded — a literal queryParam
                    // would pass '+' through unencoded (extended-year instants), which the
                    // server decodes to a space and 400s confusingly.
                    if (at.isPresent()) {
                        return uri.queryParam("at", "{at}").build(id, at.get().toString());
                    }
                    return uri.build(id);
                })
                .retrieve()
                .body(Balance.class);
    }

    public StatementPage statement(UUID id, Optional<String> cursor, int limit) {
        return client.get()
                .uri(builder -> {
                    UriBuilder uri = builder.path("/api/v1/accounts/{id}/postings")
                            .queryParam("limit", limit);
                    // URI variable, not template splice: a crafted cursor containing '{'
                    // would otherwise blow up template expansion in the CONSOLE (500)
                    // instead of reaching the API's honest invalid-cursor 400. Base64url
                    // characters are unaffected by the strict encoding, so real cursors
                    // still travel verbatim.
                    if (cursor.isPresent()) {
                        return uri.queryParam("cursor", "{cursor}").build(id, cursor.get());
                    }
                    return uri.build(id);
                })
                .retrieve()
                .body(StatementPage.class);
    }

    public Entry entry(UUID id) {
        return client.get()
                .uri("/api/v1/journal-entries/{id}", id)
                .retrieve()
                .body(Entry.class);
    }
}
