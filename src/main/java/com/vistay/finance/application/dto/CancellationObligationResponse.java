package com.vistay.finance.application.dto;

import com.vistay.finance.domain.model.ReservationCancellationObligation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CancellationObligationResponse(
    UUID id,
    UUID bookingId,
    UUID propertyId,
    UUID cancellationQuoteId,
    String cancellationQuoteFingerprint,
    UUID policyRuleId,
    Integer policyRuleVersion,
    BigDecimal verifiedCollectedAmount,
    BigDecimal refundableAmount,
    BigDecimal forfeitedAmount,
    String currency,
    String specialRefundReasonCode,
    String specialRefundEvidenceRef,
    UUID refundId,
    String status,
    String failureCode,
    String failureMessage,
    UUID sourceEventId,
    String correlationId,
    Long version,
    Instant createdAt,
    Instant updatedAt
) {
    public static CancellationObligationResponse from(ReservationCancellationObligation entity) {
        return new CancellationObligationResponse(
            entity.getId(),
            entity.getBookingId(),
            entity.getPropertyId(),
            entity.getCancellationQuoteId(),
            entity.getCancellationQuoteFingerprint(),
            entity.getPolicyRuleId(),
            entity.getPolicyRuleVersion(),
            entity.getVerifiedCollectedAmount(),
            entity.getRefundableAmount(),
            entity.getForfeitedAmount(),
            entity.getCurrency(),
            entity.getSpecialRefundReasonCode(),
            entity.getSpecialRefundEvidenceRef(),
            entity.getRefundId(),
            entity.getStatus().name(),
            entity.getFailureCode(),
            entity.getFailureMessage(),
            entity.getSourceEventId(),
            entity.getCorrelationId(),
            entity.getVersion(),
            entity.getCreatedAt(),
            entity.getUpdatedAt()
        );
    }
}
