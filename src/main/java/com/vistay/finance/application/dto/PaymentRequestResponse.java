package com.vistay.finance.application.dto;

import com.vistay.finance.domain.model.PayerType;
import com.vistay.finance.domain.model.PaymentRequest;
import com.vistay.finance.domain.model.PaymentRequestStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentRequestResponse(
    UUID id,
    String requestCode,
    UUID bookingId,
    PayerType payerType,
    UUID payerId,
    BigDecimal amount,
    String currency,
    String purpose,
    PaymentRequestStatus status,
    String idempotencyKey,
    Instant expiresAt,
    Instant createdAt,
    Instant updatedAt
) {
    public static PaymentRequestResponse fromEntity(PaymentRequest entity) {
        return new PaymentRequestResponse(
            entity.getId(),
            entity.getRequestCode(),
            entity.getBookingId(),
            entity.getPayerType(),
            entity.getPayerId(),
            entity.getAmount(),
            entity.getCurrency(),
            entity.getPurpose(),
            entity.getStatus(),
            entity.getIdempotencyKey(),
            entity.getExpiresAt(),
            entity.getCreatedAt(),
            entity.getUpdatedAt()
        );
    }
}
