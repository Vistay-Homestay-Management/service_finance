package com.vistay.finance.domain.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "reservation_amendment_assessments", schema = "app")
public class ReservationAmendmentAssessment {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id = UUID.randomUUID();

    @Column(name = "booking_id", nullable = false, updatable = false)
    private UUID bookingId;

    @Column(name = "property_id", nullable = false, updatable = false)
    private UUID propertyId;

    @Column(name = "customer_id")
    private UUID customerId;

    @Column(name = "proposal_id", nullable = false, updatable = false, length = 120)
    private String proposalId;

    @Column(name = "amendment_revision", nullable = false, updatable = false)
    private long amendmentRevision = 1L;

    @Column(name = "is_current", nullable = false)
    private boolean current = true;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "before_snapshot_fingerprint", nullable = false, length = 64, columnDefinition = "char(64)")
    private String beforeSnapshotFingerprint;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "after_snapshot_fingerprint", nullable = false, length = 64, columnDefinition = "char(64)")
    private String afterSnapshotFingerprint;

    @Column(name = "rule_catalogue_id")
    private UUID ruleCatalogueId;

    @Column(name = "rule_version", nullable = false)
    private int ruleVersion;

    @Column(name = "before_required_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal beforeRequiredAmount;

    @Column(name = "after_required_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal afterRequiredAmount;

    @Column(name = "verified_collected_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal verifiedCollectedAmount;

    @Column(name = "delta_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal deltaAmount;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, length = 3, columnDefinition = "char(3)")
    private String currency = "VND";

    @Enumerated(EnumType.STRING)
    @Column(name = "required_action", nullable = false, length = 24)
    private AmendmentRequiredAction requiredAction;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private AmendmentAssessmentStatus status;

    @Column(name = "waiver_reason", length = 1000)
    private String waiverReason;

    @Column(name = "assessed_by_actor_id")
    private UUID assessedByActorId;

    @Column(name = "approved_by_admin_user_id")
    private UUID approvedByAdminUserId;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "executed_event_id", unique = true)
    private UUID executedEventId;

    @Column(name = "resulting_receivable_id")
    private UUID resultingReceivableId;

    @Column(name = "resulting_refund_id")
    private UUID resultingRefundId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 160)
    private String idempotencyKey;

    @Column(name = "correlation_id", nullable = false, length = 100)
    private String correlationId;

    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected ReservationAmendmentAssessment() {
    }

    public ReservationAmendmentAssessment(
        UUID id,
        UUID bookingId,
        UUID propertyId,
        UUID customerId,
        String proposalId,
        long amendmentRevision,
        String beforeSnapshotFingerprint,
        String afterSnapshotFingerprint,
        UUID ruleCatalogueId,
        int ruleVersion,
        BigDecimal beforeRequiredAmount,
        BigDecimal afterRequiredAmount,
        BigDecimal verifiedCollectedAmount,
        BigDecimal deltaAmount,
        String currency,
        AmendmentRequiredAction requiredAction,
        AmendmentAssessmentStatus status,
        UUID assessedByActorId,
        Instant expiresAt,
        String idempotencyKey,
        String correlationId
    ) {
        this.id = id != null ? id : UUID.randomUUID();
        this.bookingId = Objects.requireNonNull(bookingId, "bookingId is required");
        this.propertyId = Objects.requireNonNull(propertyId, "propertyId is required");
        this.customerId = customerId;
        if (proposalId == null || proposalId.isBlank()) {
            throw new IllegalArgumentException("proposalId cannot be blank");
        }
        this.proposalId = proposalId.trim();
        if (amendmentRevision < 1) {
            throw new IllegalArgumentException("amendmentRevision must be >= 1");
        }
        this.amendmentRevision = amendmentRevision;
        this.beforeSnapshotFingerprint = requireFingerprint(beforeSnapshotFingerprint, "beforeSnapshotFingerprint");
        this.afterSnapshotFingerprint = requireFingerprint(afterSnapshotFingerprint, "afterSnapshotFingerprint");
        this.ruleCatalogueId = ruleCatalogueId;
        if (ruleVersion < 1) {
            throw new IllegalArgumentException("ruleVersion must be >= 1");
        }
        this.ruleVersion = ruleVersion;
        this.beforeRequiredAmount = nonNegative(beforeRequiredAmount, "beforeRequiredAmount");
        this.afterRequiredAmount = nonNegative(afterRequiredAmount, "afterRequiredAmount");
        this.verifiedCollectedAmount = nonNegative(verifiedCollectedAmount, "verifiedCollectedAmount");
        this.deltaAmount = Objects.requireNonNull(deltaAmount, "deltaAmount is required");
        this.currency = (currency != null && !currency.isBlank()) ? currency.trim().toUpperCase() : "VND";
        this.requiredAction = Objects.requireNonNull(requiredAction, "requiredAction is required");
        this.status = Objects.requireNonNull(status, "status is required");
        this.assessedByActorId = assessedByActorId;
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt is required");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey cannot be blank");
        }
        this.idempotencyKey = idempotencyKey.trim();
        if (correlationId == null || correlationId.isBlank()) {
            throw new IllegalArgumentException("correlationId cannot be blank");
        }
        this.correlationId = correlationId.trim();
        this.current = true;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void markStale(Instant at) {
        if (this.status == AmendmentAssessmentStatus.EXECUTED) {
            throw new IllegalStateException("Cannot mark EXECUTED amendment assessment as STALE");
        }
        this.status = AmendmentAssessmentStatus.STALE;
        this.current = false;
        this.updatedAt = at != null ? at : Instant.now();
    }

    public void verifyCollectionSatisfied(BigDecimal updatedVerifiedCollected, Instant at) {
        if (this.status == AmendmentAssessmentStatus.EXECUTED || this.status == AmendmentAssessmentStatus.STALE) {
            throw new IllegalStateException("Cannot verify collection in status " + this.status);
        }
        this.verifiedCollectedAmount = nonNegative(updatedVerifiedCollected, "updatedVerifiedCollected");
        if (this.verifiedCollectedAmount.compareTo(this.afterRequiredAmount) < 0) {
            throw new IllegalStateException("Collected amount is still below required amendment deposit/payment");
        }
        this.deltaAmount = this.afterRequiredAmount.subtract(this.verifiedCollectedAmount);
        this.status = AmendmentAssessmentStatus.VERIFIED;
        this.updatedAt = at != null ? at : Instant.now();
    }

    public void approveWaiver(UUID checkerAdminUserId, String reason, Instant at) {
        if (this.status != AmendmentAssessmentStatus.WAIVER_REVIEW
            && this.status != AmendmentAssessmentStatus.COLLECTION_REQUIRED) {
            throw new IllegalStateException("Waiver approval not allowed from status " + this.status);
        }
        if (checkerAdminUserId == null) {
            throw new IllegalArgumentException("checkerAdminUserId is required");
        }
        if (this.assessedByActorId != null && this.assessedByActorId.equals(checkerAdminUserId)) {
            throw new IllegalStateException("Maker-Checker violation: assessor cannot approve their own amendment waiver");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("waiver reason is required");
        }
        Instant now = at != null ? at : Instant.now();
        if (!this.expiresAt.isAfter(now)) {
            throw new IllegalStateException("HOLD_OR_PROPOSAL_EXPIRED");
        }
        this.approvedByAdminUserId = checkerAdminUserId;
        this.approvedAt = now;
        this.waiverReason = reason.trim();
        this.status = AmendmentAssessmentStatus.WAIVED_FINAL;
        this.updatedAt = now;
    }

    public void markExecuted(UUID executedEventId, UUID resultingReceivableId, UUID resultingRefundId, Instant at) {
        if (!this.status.isExecutableFinal()) {
            throw new IllegalStateException("Amendment assessment must be VERIFIED or WAIVED_FINAL before execution, was " + this.status);
        }
        this.executedEventId = Objects.requireNonNull(executedEventId, "executedEventId is required");
        this.resultingReceivableId = resultingReceivableId;
        this.resultingRefundId = resultingRefundId;
        this.status = AmendmentAssessmentStatus.EXECUTED;
        this.updatedAt = at != null ? at : Instant.now();
    }

    public boolean matchesFingerprints(String beforeFingerprint, String afterFingerprint) {
        return Objects.equals(this.beforeSnapshotFingerprint, beforeFingerprint)
            && Objects.equals(this.afterSnapshotFingerprint, afterFingerprint);
    }

    private static String requireFingerprint(String fp, String field) {
        if (fp == null || fp.trim().length() != 64) {
            throw new IllegalArgumentException(field + " must be a 64-character SHA-256 hex digest");
        }
        return fp.trim().toLowerCase();
    }

    private static BigDecimal nonNegative(BigDecimal value, String field) {
        if (value == null || value.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException(field + " must be non-negative");
        }
        return value;
    }

    public UUID getId() { return id; }
    public UUID getBookingId() { return bookingId; }
    public UUID getPropertyId() { return propertyId; }
    public UUID getCustomerId() { return customerId; }
    public String getProposalId() { return proposalId; }
    public long getAmendmentRevision() { return amendmentRevision; }
    public boolean isCurrent() { return current; }
    public String getBeforeSnapshotFingerprint() { return beforeSnapshotFingerprint; }
    public String getAfterSnapshotFingerprint() { return afterSnapshotFingerprint; }
    public UUID getRuleCatalogueId() { return ruleCatalogueId; }
    public int getRuleVersion() { return ruleVersion; }
    public BigDecimal getBeforeRequiredAmount() { return beforeRequiredAmount; }
    public BigDecimal getAfterRequiredAmount() { return afterRequiredAmount; }
    public BigDecimal getVerifiedCollectedAmount() { return verifiedCollectedAmount; }
    public BigDecimal getDeltaAmount() { return deltaAmount; }
    public String getCurrency() { return currency; }
    public AmendmentRequiredAction getRequiredAction() { return requiredAction; }
    public AmendmentAssessmentStatus getStatus() { return status; }
    public String getWaiverReason() { return waiverReason; }
    public UUID getAssessedByActorId() { return assessedByActorId; }
    public UUID getApprovedByAdminUserId() { return approvedByAdminUserId; }
    public Instant getApprovedAt() { return approvedAt; }
    public UUID getExecutedEventId() { return executedEventId; }
    public UUID getResultingReceivableId() { return resultingReceivableId; }
    public UUID getResultingRefundId() { return resultingRefundId; }
    public Instant getExpiresAt() { return expiresAt; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getCorrelationId() { return correlationId; }
    public Long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
