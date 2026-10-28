package com.vistay.finance.application.dto;

import com.vistay.finance.domain.model.AdjustmentType;
import com.vistay.finance.domain.model.SettlementAdjustment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record SettlementAdjustmentResponse(
    UUID id,
    UUID settlementPeriodId,
    AdjustmentType adjustmentType,
    BigDecimal amount,
    String currency,
    String reason,
    UUID createdByAdminUserId,
    Instant createdAt
) {
    public static SettlementAdjustmentResponse from(SettlementAdjustment adj) {
        return new SettlementAdjustmentResponse(
            adj.getId(),
            adj.getSettlementPeriodId(),
            adj.getAdjustmentType(),
            adj.getAmount(),
            adj.getCurrency(),
            adj.getReason(),
            adj.getCreatedByAdminUserId(),
            adj.getCreatedAt()
        );
    }
}

