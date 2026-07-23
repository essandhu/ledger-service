package io.github.essandhu.ledger.adapter.web;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * POST /transfers body (PLAN §5): the two-leg convenience over the posting engine. One
 * {@link MoneyDto}, so the pair is same-currency and balanced by construction; the amount is
 * strictly positive ({@link PositiveAmount}) because PLAN §5 pins the roles — source = debit =
 * {@code +amount}, target = credit = {@code −amount} — and a non-positive amount would only
 * express role confusion. Same source and target is not a shape defect: it produces two
 * opposite legs on one account, which the ledger accepts like any balanced entry.
 */
record TransferRequest(
        @NotNull UUID sourceAccountId,
        @NotNull UUID targetAccountId,
        @NotNull @Valid @PositiveAmount MoneyDto amount,
        @Size(max = 500) String description) {
}
