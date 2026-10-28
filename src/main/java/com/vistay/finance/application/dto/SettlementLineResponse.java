package com.vistay.finance.application.dto;

import com.vistay.finance.domain.model.SettlementLine;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record SettlementLineResponse(
    UUID id,
    UUID settlementPeriodId,
    UUID bookingId,
    String bookingCode,
    LocalDate checkInDate,
    LocalDate checkOutDate,
    BigDecimal grossRevenue,
    BigDecimal commissionRate,
    BigDecimal commissionAmount,
    BigDecimal cleaningFee,
    BigDecimal netPayout,
    String currency,
    Instant createdAt
) {
    public static SettlementLineResponse from(SettlementLine line) {
        return new SettlementLineResponse(
            line.getId(),
            line.getSettlementPeriodId(),
            line.getBookingId(),
            line.getBookingCode(),
            line.getCheckInDate(),
            line.getCheckOutDate(),
            line.getGrossRevenue(),
            line.getCommissionRate(),
            line.getCommissionAmount(),
            line.getCleaningFee(),
            line.getNetPayout(),
            line.getCurrency(),
            line.getCreatedAt()
        );
    }
}

