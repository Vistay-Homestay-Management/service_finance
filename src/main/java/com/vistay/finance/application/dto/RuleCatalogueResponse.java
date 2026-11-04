package com.vistay.finance.application.dto;

import com.vistay.finance.domain.model.RuleCatalogue;
import com.vistay.finance.domain.model.RuleStatus;
import com.vistay.finance.domain.model.RuleType;

import java.time.Instant;
import java.util.UUID;

public record RuleCatalogueResponse(
        UUID id,
        String ruleCode,
        String ruleName,
        RuleType ruleType,
        int ruleVersion,
        long optLockVersion,
        String description,
        String parametersJson,
        Instant effectiveFrom,
        Instant effectiveTo,
        RuleStatus status,
        String createdByAdminUserId,
        Instant submittedAt,
        String approvedByAdminUserId,
        Instant approvedAt,
        String rejectionReason,
        Instant createdAt,
        Instant updatedAt
) {
    public static RuleCatalogueResponse from(RuleCatalogue r) {
        return new RuleCatalogueResponse(
                r.getId(),
                r.getRuleCode(),
                r.getRuleName(),
                r.getRuleType(),
                r.getRuleVersion(),
                r.getOptLockVersion(),
                r.getDescription(),
                r.getParametersJson(),
                r.getEffectiveFrom(),
                r.getEffectiveTo(),
                r.getStatus(),
                r.getCreatedByAdminUserId(),
                r.getSubmittedAt(),
                r.getApprovedByAdminUserId(),
                r.getApprovedAt(),
                r.getRejectionReason(),
                r.getCreatedAt(),
                r.getUpdatedAt()
        );
    }
}

