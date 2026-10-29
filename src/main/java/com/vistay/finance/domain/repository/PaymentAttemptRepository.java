package com.vistay.finance.domain.repository;

import com.vistay.finance.domain.model.PaymentAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, UUID> {

    List<PaymentAttempt> findByPaymentRequestIdOrderByAttemptNumberDesc(UUID paymentRequestId);

    Optional<PaymentAttempt> findByPaymentProviderAndExternalTransactionId(String paymentProvider, String externalTransactionId);

    @Query("SELECT COALESCE(MAX(a.attemptNumber), 0) FROM PaymentAttempt a WHERE a.paymentRequest.id = :requestId")
    Integer findMaxAttemptNumberByRequestId(@Param("requestId") UUID requestId);
}
