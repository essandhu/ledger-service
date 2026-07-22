package io.github.essandhu.ledger.adapter.persistence;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import io.github.essandhu.ledger.domain.model.AccountStatus;
import io.github.essandhu.ledger.domain.model.AccountType;

interface AccountJpaRepository extends JpaRepository<AccountJpaEntity, UUID> {

    Page<AccountJpaEntity> findByType(AccountType type, Pageable pageable);

    Page<AccountJpaEntity> findByStatus(AccountStatus status, Pageable pageable);

    Page<AccountJpaEntity> findByTypeAndStatus(AccountType type, AccountStatus status, Pageable pageable);
}
