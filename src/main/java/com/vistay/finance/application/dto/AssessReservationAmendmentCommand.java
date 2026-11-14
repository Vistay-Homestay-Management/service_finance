package com.vistay.finance.application.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

public record AssessReservationAmendmentCommand(
    @NotNull UUID bookingId,
    @NotBlank String proposalId,
    String ruleCode,
    @NotNull @Valid AmendmentProposalSnapshot beforeSnapshot,
    @NotNull @Valid AmendmentProposalSnapshot afterSnapshot,
    @NotNull Instant expiresAt,
    @NotBlank String idempotencyKey,
    String correlationId,
    UUID requestedByActorId,
    Long expectedVersion
) {}
