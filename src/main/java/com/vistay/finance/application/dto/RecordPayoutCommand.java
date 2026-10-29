package com.vistay.finance.application.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record RecordPayoutCommand(
        @NotNull(message = "amount cannot be null")
        @DecimalMin(value = "0.01", message = "amount must be greater than zero")
        BigDecimal amount,

        String paymentReference,

        String notes,

        UUID processedByAdminUserId
) {
}

