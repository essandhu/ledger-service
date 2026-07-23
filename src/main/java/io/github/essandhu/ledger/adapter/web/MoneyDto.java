package io.github.essandhu.ledger.adapter.web;

import jakarta.validation.constraints.NotNull;

import io.github.essandhu.ledger.domain.model.Money;

/**
 * The wire shape of money (ADR-0001): {@code {"amount": 1099, "currency": "EUR"}} — integer
 * minor units, never a decimal string; the exponent (JPY = 0, BHD = 3) is presentation owned
 * by clients. The amount must be a JSON integer LITERAL: fractional ({@code 10.99}),
 * scientific-notation ({@code 1e2} — float-shaped even when integer-valued), and quoted-string
 * ({@code "250"}) amounts all fail binding with a 400 problem — Jackson's lenient defaults
 * would truncate or coerce them, so {@code JacksonConfig} pins Float- and String-shape into
 * integer targets to Fail. A non-ISO currency fails {@link ValidCurrency} (400, same posture
 * as the account DTOs). Both requests and responses reuse this one record, so the wire
 * contract cannot fork.
 */
record MoneyDto(long amount, @NotNull @ValidCurrency String currency) {

    static MoneyDto from(Money money) {
        return new MoneyDto(money.amount(), money.currency().value());
    }
}
