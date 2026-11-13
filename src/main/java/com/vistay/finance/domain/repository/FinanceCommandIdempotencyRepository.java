package com.vistay.finance.domain.repository;

import com.vistay.finance.domain.model.FinanceCommandIdempotency;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface FinanceCommandIdempotencyRepository extends JpaRepository<FinanceCommandIdempotency, String> {

    Optional<FinanceCommandIdempotency> findByIdempotencyKey(String idempotencyKey);
}
