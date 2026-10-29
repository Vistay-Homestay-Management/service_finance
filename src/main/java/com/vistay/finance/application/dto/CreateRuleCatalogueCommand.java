package com.vistay.finance.application.dto;

import com.vistay.finance.domain.model.RuleType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record CreateRuleCatalogueCommand(
        @NotBlank @Size(max = 60) String ruleCode,
        @NotBlank @Size(max = 160) String ruleName,
        @NotNull RuleType ruleType,
        String description,
        String parametersJson,
        @NotNull Instant effectiveFrom,
        Instant effectiveTo
) {
}

