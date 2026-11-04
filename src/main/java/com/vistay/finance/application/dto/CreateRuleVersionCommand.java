package com.vistay.finance.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record CreateRuleVersionCommand(
        @NotBlank @Size(max = 160) String ruleName,
        String description,
        String parametersJson,
        @NotNull Instant effectiveFrom,
        Instant effectiveTo
) {
}

