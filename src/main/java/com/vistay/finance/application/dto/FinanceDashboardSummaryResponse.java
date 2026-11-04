package com.vistay.finance.application.dto;

import java.math.BigDecimal;

public record FinanceDashboardSummaryResponse(
        BigDecimal totalGrossRevenue,
        BigDecimal totalCommissionEarned,
        BigDecimal totalCleaningFees,
        BigDecimal totalAdjustments,
        BigDecimal totalNetPayoutCalculated,
        BigDecimal totalPayableOutstanding,
        BigDecimal totalPayablePaid,
        long totalSettlementCount,
        long approvedSettlementCount,
        long paidSettlementCount
) {
}

