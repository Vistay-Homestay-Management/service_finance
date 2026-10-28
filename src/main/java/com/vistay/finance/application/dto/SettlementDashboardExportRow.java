package com.vistay.finance.application.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record SettlementDashboardExportRow(
        String periodCode,
        UUID hostId,
        UUID propertyId,
        LocalDate startDate,
        LocalDate endDate,
        LocalDate dueDate,
        BigDecimal grossRevenue,
        BigDecimal commission,
        BigDecimal cleaningFees,
        BigDecimal adjustments,
        BigDecimal netPayout,
        String currency,
        String status,
        String payableCode,
        String payableStatus,
        BigDecimal payableAmountDue,
        BigDecimal payableAmountPaid,
        Instant approvedAt,
        UUID approvedByAdminUserId
) {
}

