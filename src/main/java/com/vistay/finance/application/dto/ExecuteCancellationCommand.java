package com.vistay.finance.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

public record ExecuteCancellationCommand(
    @NotNull(message = "sourceEventId cannot be null")
    UUID sourceEventId,

    @NotNull(message = "bookingId cannot be null")
    UUID bookingId,

    @NotNull(message = "propertyId cannot be null")
    UUID propertyId,

    @NotNull(message = "cancellationQuoteId cannot be null")
    UUID cancellationQuoteId,

    @NotBlank(message = "cancellationQuoteFingerprint cannot be blank")
    String cancellationQuoteFingerprint,

    UUID policyRuleId,
    Integer policyRuleVersion,

    @NotNull(message = "verifiedCollectedAmount cannot be null")
    BigDecimal verifiedCollectedAmount,

    @NotNull(message = "refundableAmount cannot be null")
    BigDecimal refundableAmount,

    @NotNull(message = "forfeitedAmount cannot be null")
    BigDecimal forfeitedAmount,

    String currency,
    String specialRefundReasonCode,
    String specialRefundEvidenceRef,

    @NotBlank(message = "correlationId cannot be blank")
    String correlationId,

    @NotBlank(message = "idempotencyKey cannot be blank")
    String idempotencyKey
) {}
