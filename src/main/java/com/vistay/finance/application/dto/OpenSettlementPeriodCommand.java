package com.vistay.finance.application.dto;

import java.time.LocalDate;
import java.util.UUID;

public record OpenSettlementPeriodCommand(
    UUID hostId,
    UUID propertyId,
    LocalDate startDate,
    LocalDate endDate,
    LocalDate dueDate,
    String currency,
    UUID openedByAdminUserId,
    String notes
) {}

