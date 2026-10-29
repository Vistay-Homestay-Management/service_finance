package com.vistay.finance.application.dto;

import com.vistay.finance.domain.model.PayerType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CreatePaymentRequestCommand(
    UUID bookingId,
    @NotNull(message = "Payer type is required")
    PayerType payerType,
    UUID payerId,
    @NotNull(message = "Amount is required")
    @DecimalMin(value = "0.01", message = "Amount must be greater than 0")
    BigDecimal amount,
    String currency,
    @NotBlank(message = "Purpose is required")
    String purpose,
    @NotBlank(message = "Idempotency key is required")
    String idempotencyKey,
    Instant expiresAt,
    UUID createdByAdminUserId
) {}
