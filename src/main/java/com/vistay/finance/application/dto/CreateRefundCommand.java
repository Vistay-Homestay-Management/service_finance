package com.vistay.finance.application.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

public record CreateRefundCommand(
    UUID bookingId,
    @NotNull(message = "Original transaction ID is required")
    UUID originalTransactionId,
    @NotNull(message = "Refund amount is required")
    @DecimalMin(value = "0.01", message = "Refund amount must be greater than 0")
    BigDecimal amount,
    String currency,
    @NotBlank(message = "Reason is required")
    String reason,
    @NotNull(message = "Admin user ID is required")
    UUID requestedByAdminUserId,
    @NotBlank(message = "Idempotency key is required")
    String idempotencyKey
) {}
