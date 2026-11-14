package com.vistay.finance.application.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AssessDepositCommand(
        String targetKey,
        UUID bookingId,
        UUID quoteId,
        UUID holdId,
        UUID propertyId,
        UUID customerId,
        BigDecimal quoteTotalAmount,
        String currency,
        String ruleCode,
        Long trustInputVersion,
        String trustDecisionRef,
        Instant expiresAt,
        String idempotencyKey,
        String correlationId,
        UUID stayProductId,
        UUID unitId,
        String checkInDate,
        String checkOutDate
) {
    public AssessDepositCommand {
        if (targetKey == null || targetKey.isBlank()) {
            throw new IllegalArgumentException("Target key cannot be blank");
        }
        if (propertyId == null) {
            throw new IllegalArgumentException("Property ID cannot be null");
        }
        if (quoteTotalAmount == null || quoteTotalAmount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Quote total amount cannot be null or negative");
        }
        if (expiresAt == null) {
            throw new IllegalArgumentException("Expires at cannot be null");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency key cannot be blank");
        }
        if (correlationId == null || correlationId.isBlank()) {
            throw new IllegalArgumentException("Correlation ID cannot be blank");
        }
    }

    public AssessDepositCommand(
            String targetKey,
            UUID bookingId,
            UUID quoteId,
            UUID holdId,
            UUID propertyId,
            UUID customerId,
            BigDecimal quoteTotalAmount,
            String currency,
            String ruleCode,
            Long trustInputVersion,
            String trustDecisionRef,
            Instant expiresAt,
            String idempotencyKey,
            String correlationId
    ) {
        this(targetKey, bookingId, quoteId, holdId, propertyId, customerId, quoteTotalAmount,
             currency, ruleCode, trustInputVersion, trustDecisionRef, expiresAt, idempotencyKey,
             correlationId, null, null, null, null);
    }
}
