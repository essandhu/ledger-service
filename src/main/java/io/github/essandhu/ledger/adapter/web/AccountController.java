package io.github.essandhu.ledger.adapter.web;

import java.net.URI;
import java.util.Optional;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.essandhu.ledger.application.port.in.AccountFilter;
import io.github.essandhu.ledger.application.port.in.CreateAccountUseCase;
import io.github.essandhu.ledger.application.port.in.CreateAccountUseCase.CreateAccountCommand;
import io.github.essandhu.ledger.application.port.in.GetAccountQuery;
import io.github.essandhu.ledger.application.port.in.ListAccountsQuery;
import io.github.essandhu.ledger.application.port.in.PageSpec;
import io.github.essandhu.ledger.application.port.in.UpdateAccountUseCase;
import io.github.essandhu.ledger.application.port.in.UpdateAccountUseCase.UpdateAccountCommand;
import io.github.essandhu.ledger.domain.model.Account;
import io.github.essandhu.ledger.domain.model.AccountId;
import io.github.essandhu.ledger.domain.model.AccountStatus;
import io.github.essandhu.ledger.domain.model.AccountType;
import io.github.essandhu.ledger.domain.model.CurrencyCode;

/** The account management surface (PLAN §5). Thin: DTO ↔ command mapping only. */
@RestController
@RequestMapping("/api/v1/accounts")
class AccountController {

    private static final String PATCH_OPERATION = "PATCH /api/v1/accounts/{id}";

    private final CreateAccountUseCase createAccount;
    private final UpdateAccountUseCase updateAccount;
    private final GetAccountQuery getAccount;
    private final ListAccountsQuery listAccounts;

    AccountController(CreateAccountUseCase createAccount, UpdateAccountUseCase updateAccount,
            GetAccountQuery getAccount, ListAccountsQuery listAccounts) {
        this.createAccount = createAccount;
        this.updateAccount = updateAccount;
        this.getAccount = getAccount;
        this.listAccounts = listAccounts;
    }

    @PostMapping
    ResponseEntity<AccountResponse> create(@Valid @RequestBody CreateAccountRequest request) {
        if (request.status() != null) {
            throw new FieldNotWritable("status", "POST /api/v1/accounts");
        }
        Account account = createAccount.create(new CreateAccountCommand(request.name(),
                new CurrencyCode(request.currency()), request.type(), request.allowNegative()));
        return ResponseEntity
                .created(URI.create("/api/v1/accounts/" + account.id().value()))
                .body(AccountResponse.from(account));
    }

    @GetMapping("/{id}")
    AccountResponse byId(@PathVariable UUID id) {
        return AccountResponse.from(getAccount.byId(new AccountId(id)));
    }

    @GetMapping
    AccountPageResponse list(
            @RequestParam Optional<AccountType> type,
            @RequestParam Optional<AccountStatus> status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(PageSpec.MAX_SIZE) int size) {
        return AccountPageResponse.from(
                listAccounts.list(new AccountFilter(type, status), new PageSpec(page, size)));
    }

    @PatchMapping("/{id}")
    AccountResponse patch(@PathVariable UUID id, @Valid @RequestBody UpdateAccountRequest request) {
        if (request.type() != null) {
            throw new FieldNotWritable("type", PATCH_OPERATION);
        }
        if (request.currency() != null) {
            throw new FieldNotWritable("currency", PATCH_OPERATION);
        }
        if (request.allowNegative() != null) {
            throw new FieldNotWritable("allowNegative", PATCH_OPERATION);
        }
        Account account = updateAccount.update(new UpdateAccountCommand(new AccountId(id),
                Optional.ofNullable(request.name()), Optional.ofNullable(request.status())));
        return AccountResponse.from(account);
    }
}
