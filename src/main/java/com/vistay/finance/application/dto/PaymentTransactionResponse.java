package com.vistay.finance.application.dto;

import com.vistay.finance.domain.model.PaymentMethod;
import com.vistay.finance.domain.model.PaymentTransaction;
import com.vistay.finance.domain.model.TransactionStatus;
import com.vistay.finance.domain.model.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentTransactionResponse(
    UUID id,
    String transactionCode,
    UUID paymentRequestId,
    UUID paymentAttemptId,
    UUID originalTransactionId,
    UUID bookingId,
    TransactionType transactionType,
    BigDecimal amount,
    String currency,
    PaymentMethod paymentMethod,
    String paymentProvider,
    String externalTransactionId,
    TransactionStatus status,
    String idempotencyKey,
    String description,
    Instant occurredAt,
    Instant completedAt
) {
    public static PaymentTransactionResponse fromEntity(PaymentTransaction entity) {
        return new PaymentTransactionResponse(
            entity.getId(),
            entity.getTransactionCode(),
            entity.getPaymentRequestId(),
            entity.getPaymentAttemptId(),
            entity.getOriginalTransactionId(),
            entity.getBookingId(),
            entity.getTransactionType(),
            entity.getAmount(),
            entity.getCurrency(),
            entity.getPaymentMethod(),
            entity.getPaymentProvider(),
            entity.getExternalTransactionId(),
            entity.getStatus(),
            entity.getIdempotencyKey(),
            entity.getDescription(),
            entity.getOccurredAt(),
            entity.getCompletedAt()
        );
    }
}
