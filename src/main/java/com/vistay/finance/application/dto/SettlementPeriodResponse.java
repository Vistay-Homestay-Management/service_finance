package com.vistay.finance.application.dto;

import com.vistay.finance.domain.model.SettlementPeriod;
import com.vistay.finance.domain.model.SettlementStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record SettlementPeriodResponse(
    UUID id,
    String periodCode,
    UUID hostId,
    UUID propertyId,
    LocalDate startDate,
    LocalDate endDate,
    LocalDate dueDate,
    BigDecimal totalGrossRevenue,
    BigDecimal totalCommission,
    BigDecimal totalCleaningFees,
    BigDecimal totalAdjustments,
    BigDecimal totalNetPayout,
    String currency,
    SettlementStatus status,
    UUID openedByAdminUserId,
    UUID approvedByAdminUserId,
    Instant approvedAt,
    String notes,
    UUID payableId,
    Instant createdAt,
    Instant updatedAt,
    List<SettlementLineResponse> lines,
    List<SettlementAdjustmentResponse> adjustments
) {
    public static SettlementPeriodResponse from(SettlementPeriod period,
                                                List<SettlementLineResponse> lines,
                                                List<SettlementAdjustmentResponse> adjustments) {
        return new SettlementPeriodResponse(
            period.getId(),
            period.getPeriodCode(),
            period.getHostId(),
            period.getPropertyId(),
            period.getStartDate(),
            period.getEndDate(),
            period.getDueDate(),
            period.getTotalGrossRevenue(),
            period.getTotalCommission(),
            period.getTotalCleaningFees(),
            period.getTotalAdjustments(),
            period.getTotalNetPayout(),
            period.getCurrency(),
            period.getStatus(),
            period.getOpenedByAdminUserId(),
            period.getApprovedByAdminUserId(),
            period.getApprovedAt(),
            period.getNotes(),
            period.getPayableId(),
            period.getCreatedAt(),
            period.getUpdatedAt(),
            lines,
            adjustments
        );
    }
}

