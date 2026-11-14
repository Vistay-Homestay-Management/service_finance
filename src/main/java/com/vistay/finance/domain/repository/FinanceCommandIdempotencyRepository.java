package com.vistay.finance.domain.repository;

import com.vistay.finance.domain.model.FinanceCommandIdempotency;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface FinanceCommandIdempotencyRepository extends JpaRepository<FinanceCommandIdempotency, String> {

    Optional<FinanceCommandIdempotency> findByIdempotencyKey(String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM FinanceCommandIdempotency c WHERE c.idempotencyKey = :idempotencyKey")
    Optional<FinanceCommandIdempotency> findByIdempotencyKeyForUpdate(@Param("idempotencyKey") String idempotencyKey);
}
