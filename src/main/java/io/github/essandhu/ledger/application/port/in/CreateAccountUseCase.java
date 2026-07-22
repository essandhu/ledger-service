package io.github.essandhu.ledger.application.port.in;

import java.util.Objects;

import io.github.essandhu.ledger.domain.model.Account;
import io.github.essandhu.ledger.domain.model.AccountType;
import io.github.essandhu.ledger.domain.model.CurrencyCode;

/** POST /accounts (PLAN §5): open a new ACTIVE account. */
public interface CreateAccountUseCase {

    Account create(CreateAccountCommand command);

    record CreateAccountCommand(String name, CurrencyCode currency, AccountType type, boolean allowNegative) {
        public CreateAccountCommand {
            Objects.requireNonNull(currency, "currency");
            Objects.requireNonNull(type, "type");
            // name is validated by the domain (Account.open) so the rule lives in one place
        }
    }
}
