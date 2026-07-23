package io.github.essandhu.ledger.adapter.web;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * One leg of a POST /journal-entries body (PLAN §5): an account plus a signed debit-positive
 * amount in minor units (PLAN §4.2). Shape only — whether the legs balance (I1), count at
 * least two (I2), or reference existing ACTIVE accounts is the domain's and the use case's
 * verdict, each with its own 422.
 */
record PostingLegRequest(@NotNull UUID accountId, @NotNull @Valid MoneyDto amount) {
}
