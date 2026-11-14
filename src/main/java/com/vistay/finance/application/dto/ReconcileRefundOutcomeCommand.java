package com.vistay.finance.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

public record ReconcileRefundOutcomeCommand(
    @NotNull(message = "obligationId cannot be null")
    UUID obligationId,

    @NotNull(message = "refundId cannot be null")
    UUID refundId,

    boolean success,

    BigDecimal amount,

    String currency,

    String failureCode,

    String failureMessage,

    String paymentProvider,

    String externalTransactionId,

    @NotBlank(message = "correlationId cannot be blank")
    String correlationId
) {
    public ReconcileRefundOutcomeCommand(
        UUID obligationId,
        UUID refundId,
        boolean success,
        String failureCode,
        String failureMessage,
        String paymentProvider,
        String externalTransactionId,
        String correlationId
    ) {
        this(obligationId, refundId, success, null, null, failureCode, failureMessage, paymentProvider, externalTransactionId, correlationId);
    }

    public ReconcileRefundOutcomeCommand(
        UUID obligationId,
        UUID refundId,
        boolean success,
        String failureCode,
        String failureMessage,
        String correlationId
    ) {
        this(obligationId, refundId, success, null, null, failureCode, failureMessage, null, null, correlationId);
    }
}
