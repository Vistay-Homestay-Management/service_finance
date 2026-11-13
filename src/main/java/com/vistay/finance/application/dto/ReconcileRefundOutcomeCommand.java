package com.vistay.finance.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record ReconcileRefundOutcomeCommand(
    @NotNull(message = "obligationId cannot be null")
    UUID obligationId,

    UUID refundId,

    boolean success,

    String failureCode,

    String failureMessage,

    @NotBlank(message = "correlationId cannot be blank")
    String correlationId
) {}
