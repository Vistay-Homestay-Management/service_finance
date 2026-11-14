package com.vistay.finance.application.dto;

import java.util.UUID;

public record WaiveDepositCommand(
        String targetKey,
        UUID approverAdminId,
        String reason,
        Long expectedRevision,
        Long expectedVersion,
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
        if (expectedRevision == null || expectedRevision < 1) {
            throw new IllegalArgumentException("Expected revision is mandatory and must be >= 1");
        }
        if (expectedVersion == null || expectedVersion < 0) {
            throw new IllegalArgumentException("Expected version is mandatory and must be >= 0");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency key cannot be blank");
        }
        if (correlationId == null || correlationId.isBlank()) {
            throw new IllegalArgumentException("Correlation ID cannot be blank");
        }
    }
}

