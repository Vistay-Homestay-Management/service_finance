package com.vistay.finance.application.dto;

import com.vistay.finance.domain.model.Refund;
import com.vistay.finance.domain.model.RefundStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record RefundResponse(
    UUID id,
    String refundCode,
    UUID bookingId,
    UUID originalTransactionId,
    UUID refundTransactionId,
    BigDecimal amount,
    String currency,
    String reason,
    RefundStatus status,
    UUID requestedByAdminUserId,
    UUID approvedByAdminUserId,
    String externalRefundId,
    String idempotencyKey,
    Instant requestedAt,
    Instant approvedAt,
    Instant completedAt
) {
    public static RefundResponse fromEntity(Refund entity) {
        return new RefundResponse(
            entity.getId(),
            entity.getRefundCode(),
            entity.getBookingId(),
            entity.getOriginalTransactionId(),
            entity.getRefundTransactionId(),
            entity.getAmount(),
            entity.getCurrency(),
            entity.getReason(),
            entity.getStatus(),
            entity.getRequestedByAdminUserId(),
            entity.getApprovedByAdminUserId(),
            entity.getExternalRefundId(),
            entity.getIdempotencyKey(),
            entity.getRequestedAt(),
            entity.getApprovedAt(),
            entity.getCompletedAt()
        );
    }
}
