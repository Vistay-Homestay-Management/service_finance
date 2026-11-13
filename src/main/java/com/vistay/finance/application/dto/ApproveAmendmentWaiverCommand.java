package com.vistay.finance.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record ApproveAmendmentWaiverCommand(
    @NotNull UUID bookingId,
    @NotBlank String proposalId,
    @NotNull Long expectedVersion,
    @NotBlank String reason,
    @NotBlank String idempotencyKey,
    String correlationId,
    UUID approvedByAdminUserId
) {}
