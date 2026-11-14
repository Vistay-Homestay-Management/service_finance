package com.vistay.finance.domain.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "reservation_deposit_assessments", schema = "app")
public class ReservationDepositAssessment {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "target_key", nullable = false, length = 120)
    private String targetKey;

    @Column(name = "booking_id")
    private UUID bookingId;

    @Column(name = "quote_id")
    private UUID quoteId;

    @Column(name = "hold_id")
    private UUID holdId;

    @Column(name = "property_id", nullable = false)
    private UUID propertyId;

    @Column(name = "customer_id")
    private UUID customerId;

    @Column(name = "assessment_revision", nullable = false)
    private long assessmentRevision = 1;

    @Column(name = "is_current", nullable = false)
    private boolean isCurrent = true;

    @Column(name = "input_fingerprint", nullable = false, length = 64)
    private String inputFingerprint;

    @Column(name = "rule_catalogue_id")
    private UUID ruleCatalogueId;

    @Column(name = "rule_version", nullable = false)
    private int ruleVersion;

    @Column(name = "trust_input_version")
    private Long trustInputVersion;

    @Column(name = "trust_decision_ref", length = 120)
    private String trustDecisionRef;

    @Column(name = "required_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal requiredAmount;

    @Column(name = "collected_verified_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal collectedVerifiedAmount = BigDecimal.ZERO;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, length = 3, columnDefinition = "char(3)")
    private String currency = "VND";

    @Enumerated(EnumType.STRING)
    @Column(name = "waiver_mode", nullable = false, length = 32)
    private DepositWaiverMode waiverMode = DepositWaiverMode.NONE;

    @Column(name = "waiver_reason", length = 1000)
    private String waiverReason;

    @Column(name = "waiver_approved_by_admin_user_id")
    private UUID waiverApprovedByAdminUserId;

    @Column(name = "waiver_approved_at")
    private Instant waiverApprovedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private DepositAssessmentStatus status;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 160)
    private String idempotencyKey;

    @Column(name = "correlation_id", nullable = false, length = 100)
    private String correlationId;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected ReservationDepositAssessment() {
    }

    public ReservationDepositAssessment(String targetKey,
                                        UUID bookingId,
                                        UUID quoteId,
                                        UUID holdId,
                                        UUID propertyId,
                                        UUID customerId,
                                        long assessmentRevision,
                                        String inputFingerprint,
                                        UUID ruleCatalogueId,
                                        int ruleVersion,
                                        Long trustInputVersion,
                                        String trustDecisionRef,
                                        BigDecimal requiredAmount,
                                        BigDecimal collectedVerifiedAmount,
                                        String currency,
                                        DepositWaiverMode waiverMode,
                                        DepositAssessmentStatus status,
                                        Instant expiresAt,
                                        String idempotencyKey,
                                        String correlationId) {
        if (targetKey == null || targetKey.isBlank()) {
            throw new IllegalArgumentException("Target key cannot be blank");
        }
        if (propertyId == null) {
            throw new IllegalArgumentException("Property ID cannot be null");
        }
        if (inputFingerprint == null || inputFingerprint.isBlank()) {
            throw new IllegalArgumentException("Input fingerprint cannot be blank");
        }
        if (requiredAmount == null || requiredAmount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Required amount cannot be null or negative");
        }
        if (expiresAt == null) {
            throw new IllegalArgumentException("Expires at cannot be null");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency key cannot be blank");
        }
        if (correlationId == null || correlationId.isBlank()) {
            throw new IllegalArgumentException("Correlation ID cannot be blank");
        }

        this.targetKey = targetKey.trim();
        this.bookingId = bookingId;
        this.quoteId = quoteId;
        this.holdId = holdId;
        this.propertyId = propertyId;
        this.customerId = customerId;
        this.assessmentRevision = assessmentRevision;
        this.isCurrent = true;
        this.inputFingerprint = inputFingerprint.trim();
        this.ruleCatalogueId = ruleCatalogueId;
        this.ruleVersion = ruleVersion;
        this.trustInputVersion = trustInputVersion;
        this.trustDecisionRef = trustDecisionRef;
        this.requiredAmount = requiredAmount;
        this.collectedVerifiedAmount = (collectedVerifiedAmount != null) ? collectedVerifiedAmount : BigDecimal.ZERO;
        this.currency = (currency != null && !currency.isBlank()) ? currency.trim().toUpperCase() : "VND";
        this.waiverMode = (waiverMode != null) ? waiverMode : DepositWaiverMode.NONE;
        this.status = (status != null) ? status : DepositAssessmentStatus.PENDING;
        this.expiresAt = expiresAt;
        this.idempotencyKey = idempotencyKey.trim();
        this.correlationId = correlationId.trim();
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void applyWaiver(UUID approverAdminId, String reason) {
        if (approverAdminId == null) {
            throw new IllegalArgumentException("Approver admin user ID is required for waiver");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Waiver reason cannot be blank");
        }
        if (this.status == DepositAssessmentStatus.VERIFIED_PAID) {
            throw new IllegalStateException("Cannot waive a deposit that has already been verified and paid");
        }
        if (this.status == DepositAssessmentStatus.STALE) {
            throw new IllegalStateException("Cannot waive a deposit assessment that has expired / is stale");
        }

        this.waiverMode = DepositWaiverMode.MANUAL_WAIVER;
        this.waiverApprovedByAdminUserId = approverAdminId;
        this.waiverApprovedAt = Instant.now();
        this.waiverReason = reason.trim();
        this.status = DepositAssessmentStatus.WAIVED_FINAL;
        this.updatedAt = Instant.now();
    }

    public void recordVerifiedCollection(BigDecimal collectedAmount) {
        if (collectedAmount == null || collectedAmount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Collected amount cannot be null or negative");
        }
        this.collectedVerifiedAmount = collectedAmount;
        if (this.collectedVerifiedAmount.compareTo(this.requiredAmount) >= 0) {
            this.status = DepositAssessmentStatus.VERIFIED_PAID;
        } else if (this.expiresAt.isBefore(Instant.now())) {
            this.status = DepositAssessmentStatus.STALE;
        } else {
            this.status = DepositAssessmentStatus.PAYMENT_REQUIRED;
        }
        this.updatedAt = Instant.now();
    }

    public void supersede() {
        this.isCurrent = false;
        this.updatedAt = Instant.now();
    }

    public void markStale() {
        this.status = DepositAssessmentStatus.STALE;
        this.updatedAt = Instant.now();
    }

    // Getters
    public UUID getId() { return id; }
    public String getTargetKey() { return targetKey; }
    public UUID getBookingId() { return bookingId; }
    public UUID getQuoteId() { return quoteId; }
    public UUID getHoldId() { return holdId; }
    public UUID getPropertyId() { return propertyId; }
    public UUID getCustomerId() { return customerId; }
    public long getAssessmentRevision() { return assessmentRevision; }
    public boolean isCurrent() { return isCurrent; }
    public String getInputFingerprint() { return inputFingerprint; }
    public UUID getRuleCatalogueId() { return ruleCatalogueId; }
    public int getRuleVersion() { return ruleVersion; }
    public Long getTrustInputVersion() { return trustInputVersion; }
    public String getTrustDecisionRef() { return trustDecisionRef; }
    public BigDecimal getRequiredAmount() { return requiredAmount; }
    public BigDecimal getCollectedVerifiedAmount() { return collectedVerifiedAmount; }
    public String getCurrency() { return currency; }
    public DepositWaiverMode getWaiverMode() { return waiverMode; }
    public String getWaiverReason() { return waiverReason; }
    public UUID getWaiverApprovedByAdminUserId() { return waiverApprovedByAdminUserId; }
    public Instant getWaiverApprovedAt() { return waiverApprovedAt; }
    public DepositAssessmentStatus getStatus() { return status; }
    public Instant getExpiresAt() { return expiresAt; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getCorrelationId() { return correlationId; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
