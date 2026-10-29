package com.vistay.finance.application.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record CalculateSettlementCommand(
    List<SettlementBookingItemDto> bookingItems
) {
    public record SettlementBookingItemDto(
        UUID bookingId,
        String bookingCode,
        LocalDate checkInDate,
        LocalDate checkOutDate,
        BigDecimal grossRevenue,
        BigDecimal commissionRate,
        BigDecimal cleaningFee,
        String currency
    ) {}
}

