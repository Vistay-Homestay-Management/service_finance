package com.vistay.finance.domain.model;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "finance_rule_catalogues", schema = "app")
public class RuleCatalogue {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id = UUID.randomUUID();

    @Column(name = "rule_code", nullable = false, length = 60)
    private String ruleCode;

    @Column(name = "rule_name", nullable = false, length = 160)
    private String ruleName;

    @Enumerated(EnumType.STRING)
    @Column(name = "rule_type", nullable = false, length = 32)
    private RuleType ruleType;

    @Column(name = "rule_version", nullable = false)
    private int ruleVersion = 1;

    @Version
    @Column(name = "opt_lock_version", nullable = false)
    private long optLockVersion = 0;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "parameters_json", nullable = false, columnDefinition = "jsonb")
    private String parametersJson = "{}";

    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;

    @Column(name = "effective_to")
    private Instant effectiveTo;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private RuleStatus status = RuleStatus.DRAFT;

    @Column(name = "created_by_admin_user_id", nullable = false, updatable = false, length = 100)
    private String createdByAdminUserId;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "approved_by_admin_user_id", length = 100)
    private String approvedByAdminUserId;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "rejection_reason", length = 1000)
    private String rejectionReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected RuleCatalogue() {
    }

    public RuleCatalogue(String ruleCode,
                         String ruleName,
                         RuleType ruleType,
                         String description,
                         String parametersJson,
                         Instant effectiveFrom,
                         Instant effectiveTo,
                         String createdByAdminUserId) {
        this(ruleCode, ruleName, ruleType, 1, description, parametersJson, effectiveFrom, effectiveTo, createdByAdminUserId);
    }

    public RuleCatalogue(String ruleCode,
                         String ruleName,
                         RuleType ruleType,
                         int ruleVersion,
                         String description,
                         String parametersJson,
                         Instant effectiveFrom,
                         Instant effectiveTo,
                         String createdByAdminUserId) {
        if (ruleCode == null || ruleCode.isBlank()) {
            throw new IllegalArgumentException("Rule code cannot be blank");
        }
        if (ruleName == null || ruleName.isBlank()) {
            throw new IllegalArgumentException("Rule name cannot be blank");
        }
        if (ruleType == null) {
            throw new IllegalArgumentException("Rule type cannot be null");
        }
        if (ruleVersion < 1) {
            throw new IllegalArgumentException("Rule version must be greater than or equal to 1");
        }
        if (effectiveFrom == null) {
            throw new IllegalArgumentException("Effective from cannot be null");
        }
        if (effectiveTo != null && !effectiveTo.isAfter(effectiveFrom)) {
            throw new IllegalArgumentException("Effective to must be strictly after effective from");
        }
        if (createdByAdminUserId == null || createdByAdminUserId.isBlank()) {
            throw new IllegalArgumentException("Creator actor identity cannot be null or blank");
        }

        this.ruleCode = ruleCode.trim();
        this.ruleName = ruleName.trim();
        this.ruleType = ruleType;
        this.ruleVersion = ruleVersion;
        this.description = description != null ? description.trim() : null;
        this.parametersJson = (parametersJson != null && !parametersJson.isBlank()) ? parametersJson.trim() : "{}";
        this.effectiveFrom = effectiveFrom;
        this.effectiveTo = effectiveTo;
        this.createdByAdminUserId = createdByAdminUserId.trim();
        this.status = RuleStatus.DRAFT;
        this.optLockVersion = 0;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void submitForApproval() {
        if (status != RuleStatus.DRAFT && status != RuleStatus.REJECTED) {
            throw new IllegalStateException("Only DRAFT or REJECTED rules can be submitted for approval. Current status: " + status);
        }
        this.status = RuleStatus.PENDING_APPROVAL;
        this.submittedAt = Instant.now();
        this.updatedAt = Instant.now();
        this.rejectionReason = null;
    }

    public void approve(String checkerActorId) {
        if (status != RuleStatus.PENDING_APPROVAL) {
            throw new IllegalStateException("Only PENDING_APPROVAL rules can be approved. Current status: " + status);
        }
        if (checkerActorId == null || checkerActorId.isBlank()) {
            throw new IllegalArgumentException("Approver actor identity cannot be null or blank");
        }
        String trimmedApprover = checkerActorId.trim();
        if (Objects.equals(this.createdByAdminUserId, trimmedApprover)) {
            throw new IllegalStateException("Maker-Checker violation: Creator cannot approve their own rule catalogue");
        }

        this.status = RuleStatus.ACTIVE;
        this.approvedByAdminUserId = trimmedApprover;
        this.approvedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void reject(String checkerActorId, String reason) {
        if (status != RuleStatus.PENDING_APPROVAL) {
            throw new IllegalStateException("Only PENDING_APPROVAL rules can be rejected. Current status: " + status);
        }
        if (checkerActorId == null || checkerActorId.isBlank()) {
            throw new IllegalArgumentException("Reviewer actor identity cannot be null or blank");
        }
        String trimmedReviewer = checkerActorId.trim();
        if (Objects.equals(this.createdByAdminUserId, trimmedReviewer)) {
            throw new IllegalStateException("Maker-Checker violation: Creator cannot reject/review their own rule catalogue");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Rejection reason cannot be blank");
        }

        this.status = RuleStatus.REJECTED;
        this.approvedByAdminUserId = trimmedReviewer;
        this.rejectionReason = reason.trim();
        this.updatedAt = Instant.now();
    }

    public void archive() {
        if (status != RuleStatus.ACTIVE) {
            throw new IllegalStateException("Only ACTIVE rules can be archived. Current status: " + status);
        }
        this.status = RuleStatus.ARCHIVED;
        this.updatedAt = Instant.now();
    }

    /**
     * Checks if this rule is ACTIVE and effective at the given time instant based on half-open interval [effectiveFrom, effectiveTo).
     */
    public boolean isEffectiveAt(Instant time) {
        if (status != RuleStatus.ACTIVE) {
            return false;
        }
        if (time.isBefore(effectiveFrom)) {
            return false;
        }
        return effectiveTo == null || time.isBefore(effectiveTo);
    }

    // Getters
    public UUID getId() {
        return id;
    }

    public String getRuleCode() {
        return ruleCode;
    }

    public String getRuleName() {
        return ruleName;
    }

    public RuleType getRuleType() {
        return ruleType;
    }

    public int getRuleVersion() {
        return ruleVersion;
    }

    public long getOptLockVersion() {
        return optLockVersion;
    }

    public String getDescription() {
        return description;
    }

    public String getParametersJson() {
        return parametersJson;
    }

    public Instant getEffectiveFrom() {
        return effectiveFrom;
    }

    public Instant getEffectiveTo() {
        return effectiveTo;
    }

    public RuleStatus getStatus() {
        return status;
    }

    public String getCreatedByAdminUserId() {
        return createdByAdminUserId;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public String getApprovedByAdminUserId() {
        return approvedByAdminUserId;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
