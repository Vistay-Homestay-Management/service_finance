package com.vistay.finance.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.UUID;

public record ReservationAmendmentExecutedCommand(
    @NotNull UUID eventId,
    @NotNull UUID bookingId,
    @NotBlank String proposalId,
    @NotBlank String afterSnapshotFingerprint,
    UUID customerId,
    LocalDate dueDate,
    String correlationId,
    UUID actorAdminUserId
) {}
