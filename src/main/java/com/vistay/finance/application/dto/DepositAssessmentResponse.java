package com.vistay.finance.application.dto;

import com.vistay.finance.domain.model.DepositAssessmentStatus;
import com.vistay.finance.domain.model.DepositWaiverMode;
import com.vistay.finance.domain.model.ReservationDepositAssessment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record DepositAssessmentResponse(
        UUID id,
        String targetKey,
        UUID bookingId,
        UUID quoteId,
        UUID holdId,
        UUID propertyId,
        UUID customerId,
        long assessmentRevision,
        boolean isCurrent,
        String inputFingerprint,
        UUID ruleCatalogueId,
        int ruleVersion,
        Long trustInputVersion,
        String trustDecisionRef,
        BigDecimal requiredAmount,
        BigDecimal collectedVerifiedAmount,
        String currency,
        DepositWaiverMode waiverMode,
        String waiverReason,
        UUID waiverApprovedByAdminUserId,
        Instant waiverApprovedAt,
        DepositAssessmentStatus status,
        Instant expiresAt,
        String idempotencyKey,
        String correlationId,
        long version,
        Instant createdAt,
        Instant updatedAt
) {
    public static DepositAssessmentResponse from(ReservationDepositAssessment entity) {
        return new DepositAssessmentResponse(
                entity.getId(),
                entity.getTargetKey(),
                entity.getBookingId(),
                entity.getQuoteId(),
                entity.getHoldId(),
                entity.getPropertyId(),
                entity.getCustomerId(),
                entity.getAssessmentRevision(),
                entity.isCurrent(),
                entity.getInputFingerprint(),
                entity.getRuleCatalogueId(),
                entity.getRuleVersion(),
                entity.getTrustInputVersion(),
                entity.getTrustDecisionRef(),
                entity.getRequiredAmount(),
                entity.getCollectedVerifiedAmount(),
                entity.getCurrency(),
                entity.getWaiverMode(),
                entity.getWaiverReason(),
                entity.getWaiverApprovedByAdminUserId(),
                entity.getWaiverApprovedAt(),
                entity.getStatus(),
                entity.getExpiresAt(),
                entity.getIdempotencyKey(),
                entity.getCorrelationId(),
                entity.getVersion(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    public DepositAssessmentResponse toSafeSummary() {
        return new DepositAssessmentResponse(
                id,
                targetKey,
                bookingId,
                quoteId,
                holdId,
                propertyId,
                customerId,
                assessmentRevision,
                isCurrent,
                null,
                ruleCatalogueId,
                ruleVersion,
                null,
                null,
                requiredAmount,
                collectedVerifiedAmount,
                currency,
                waiverMode,
                null,
                null,
                waiverApprovedAt,
                status,
                expiresAt,
                null,
                null,
                version,
                createdAt,
                updatedAt
        );
    }
}
