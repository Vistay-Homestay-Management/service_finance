package com.vistay.finance.application.dto;

import com.vistay.finance.domain.model.PaymentMethod;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

public record ProcessPaymentWebhookCommand(
    @NotNull(message = "Payment request ID is required")
    UUID paymentRequestId,
    @NotNull(message = "Payment method is required")
    PaymentMethod paymentMethod,
    @NotBlank(message = "Payment provider is required")
    String paymentProvider,
    @NotBlank(message = "External transaction ID is required")
    String externalTransactionId,
    @NotNull(message = "Paid amount is required")
    @DecimalMin(value = "0.01", message = "Paid amount must be greater than 0")
    BigDecimal paidAmount,
    String currency,
    @NotBlank(message = "Idempotency key is required")
    String idempotencyKey,
    String description
) {}
