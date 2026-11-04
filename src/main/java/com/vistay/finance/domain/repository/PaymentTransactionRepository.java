package com.vistay.finance.domain.repository;

import com.vistay.finance.domain.model.PaymentTransaction;
import com.vistay.finance.domain.model.TransactionStatus;
import com.vistay.finance.domain.model.TransactionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, UUID> {

    Optional<PaymentTransaction> findByIdempotencyKey(String idempotencyKey);

    Optional<PaymentTransaction> findByTransactionCode(String transactionCode);

    Optional<PaymentTransaction> findByPaymentProviderAndExternalTransactionId(String paymentProvider, String externalTransactionId);

    List<PaymentTransaction> findByBookingIdOrderByOccurredAtDesc(UUID bookingId);

    List<PaymentTransaction> findByPaymentRequestIdOrderByOccurredAtDesc(UUID paymentRequestId);

    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM PaymentTransaction t WHERE t.bookingId = :bookingId AND t.transactionType = 'PAYMENT' AND t.status = 'SUCCEEDED'")
    BigDecimal calculateTotalPaidAmountByBookingId(@Param("bookingId") UUID bookingId);

    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM PaymentTransaction t WHERE t.paymentRequestId = :paymentRequestId AND t.transactionType = 'PAYMENT' AND t.status = 'SUCCEEDED'")
    BigDecimal calculateTotalPaidAmountByRequestId(@Param("paymentRequestId") UUID paymentRequestId);
}
