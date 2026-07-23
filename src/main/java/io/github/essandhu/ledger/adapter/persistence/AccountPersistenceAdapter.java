package io.github.essandhu.ledger.adapter.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import io.github.essandhu.ledger.application.port.in.AccountFilter;
import io.github.essandhu.ledger.application.port.in.AccountNotFound;
import io.github.essandhu.ledger.application.port.in.AccountPage;
import io.github.essandhu.ledger.application.port.in.PageSpec;
import io.github.essandhu.ledger.application.port.out.AccountRepository;
import io.github.essandhu.ledger.domain.model.Account;
import io.github.essandhu.ledger.domain.model.AccountId;

/**
 * JPA implementation of the {@link AccountRepository} port. Transactions are owned by the
 * application services; repository calls join the surrounding transaction.
 */
@Component
class AccountPersistenceAdapter implements AccountRepository {

    private static final Sort BY_ID = Sort.by(Sort.Direction.ASC, "id");

    private final AccountJpaRepository repository;

    AccountPersistenceAdapter(AccountJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public void insert(Account account) {
        repository.save(AccountJpaEntity.fromDomain(account));
    }

    @Override
    public void update(Account account) {
        AccountJpaEntity entity = repository.findById(account.id().value())
                .orElseThrow(() -> new AccountNotFound(account.id()));
        entity.apply(account);
        // Flush here so a lost optimistic-lock race surfaces at the port boundary as Spring's
        // translated OptimisticLockingFailureException (→ 409), not as an opaque commit failure.
        repository.flush();
    }

    @Override
    public Optional<Account> findById(AccountId id) {
        return repository.findById(id.value()).map(AccountJpaEntity::toDomain);
    }

    @Override
    public List<Account> findByIds(Collection<AccountId> ids) {
        // The port promises no order and silently omits missing ids — the posting engine
        // already resolved existence from the balance-lock result (ADR-0003) before calling.
        return repository.findAllById(ids.stream().map(AccountId::value).toList()).stream()
                .map(AccountJpaEntity::toDomain)
                .toList();
    }

    @Override
    public AccountPage findAll(AccountFilter filter, PageSpec page) {
        Pageable pageable = PageRequest.of(page.page(), page.size(), BY_ID);
        Page<AccountJpaEntity> result;
        if (filter.type().isPresent() && filter.status().isPresent()) {
            result = repository.findByTypeAndStatus(filter.type().get(), filter.status().get(), pageable);
        } else if (filter.type().isPresent()) {
            result = repository.findByType(filter.type().get(), pageable);
        } else if (filter.status().isPresent()) {
            result = repository.findByStatus(filter.status().get(), pageable);
        } else {
            result = repository.findAll(pageable);
        }
        return new AccountPage(
                result.getContent().stream().map(AccountJpaEntity::toDomain).toList(),
                page.page(), page.size(), result.getTotalElements());
    }
}
