package com.vistay.finance.application.dto;

import java.util.UUID;

public record WaiveDepositCommand(
        String targetKey,
        UUID approverAdminId,
        String reason,
        String idempotencyKey,
        String correlationId
) {
    public WaiveDepositCommand {
        if (targetKey == null || targetKey.isBlank()) {
            throw new IllegalArgumentException("Target key cannot be blank");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Waiver reason cannot be blank");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency key cannot be blank");
        }
        if (correlationId == null || correlationId.isBlank()) {
            throw new IllegalArgumentException("Correlation ID cannot be blank");
        }
    }
}
