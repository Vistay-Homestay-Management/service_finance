package com.vistay.finance.domain.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
    name = "reservation_cancellation_obligations",
    schema = "app",
    indexes = {
        @Index(name = "ix_cancel_obligation_property", columnList = "property_id"),
        @Index(name = "ix_cancel_obligation_status", columnList = "status")
    }
)
public class ReservationCancellationObligation {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "booking_id", nullable = false, unique = true)
    private UUID bookingId;

    @Column(name = "property_id", nullable = false)
    private UUID propertyId;

    @Column(name = "cancellation_quote_id", nullable = false, unique = true)
    private UUID cancellationQuoteId;

    @Column(name = "cancellation_quote_fingerprint", length = 64, nullable = false)
    private String cancellationQuoteFingerprint;

    @Column(name = "policy_rule_id")
    private UUID policyRuleId;

    @Column(name = "policy_rule_version")
    private Integer policyRuleVersion;

    @Column(name = "verified_collected_amount", precision = 19, scale = 4, nullable = false)
    private BigDecimal verifiedCollectedAmount;

    @Column(name = "refundable_amount", precision = 19, scale = 4, nullable = false)
    private BigDecimal refundableAmount;

    @Column(name = "forfeited_amount", precision = 19, scale = 4, nullable = false)
    private BigDecimal forfeitedAmount;

    @Column(name = "currency", length = 3, nullable = false)
    private String currency;

    @Column(name = "special_refund_reason_code", length = 64)
    private String specialRefundReasonCode;

    @Column(name = "special_refund_evidence_ref", length = 255)
    private String specialRefundEvidenceRef;

    @Column(name = "refund_id", unique = true)
    private UUID refundId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 24, nullable = false)
    private CancellationObligationStatus status;

    @Column(name = "failure_code", length = 80)
    private String failureCode;

    @Column(name = "failure_message", length = 500)
    private String failureMessage;

    @Column(name = "source_event_id", nullable = false, unique = true)
    private UUID sourceEventId;

    @Column(name = "correlation_id", length = 100, nullable = false)
    private String correlationId;

    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected ReservationCancellationObligation() {
    }

    public ReservationCancellationObligation(
        UUID id,
        UUID bookingId,
        UUID propertyId,
        UUID cancellationQuoteId,
        String cancellationQuoteFingerprint,
        UUID policyRuleId,
        Integer policyRuleVersion,
        BigDecimal verifiedCollectedAmount,
        BigDecimal refundableAmount,
        BigDecimal forfeitedAmount,
        String currency,
        String specialRefundReasonCode,
        String specialRefundEvidenceRef,
        UUID sourceEventId,
        String correlationId
    ) {
        this.id = id != null ? id : UUID.randomUUID();
        this.bookingId = bookingId;
        this.propertyId = propertyId;
        this.cancellationQuoteId = cancellationQuoteId;
        this.cancellationQuoteFingerprint = cancellationQuoteFingerprint;
        this.policyRuleId = policyRuleId;
        this.policyRuleVersion = policyRuleVersion;
        this.verifiedCollectedAmount = verifiedCollectedAmount;
        this.refundableAmount = refundableAmount;
        this.forfeitedAmount = forfeitedAmount;
        this.currency = (currency != null && !currency.isBlank()) ? currency : "VND";
        this.specialRefundReasonCode = specialRefundReasonCode;
        this.specialRefundEvidenceRef = specialRefundEvidenceRef;
        this.sourceEventId = sourceEventId;
        this.correlationId = correlationId;

        // Determine initial status based on refundable amount
        if (refundableAmount.compareTo(BigDecimal.ZERO) > 0) {
            this.status = CancellationObligationStatus.REFUND_DUE;
        } else {
            this.status = CancellationObligationStatus.NO_REFUND;
        }

        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void markRefundRequested(UUID refundId) {
        if (this.status != CancellationObligationStatus.REFUND_DUE && this.status != CancellationObligationStatus.PENDING) {
            throw new IllegalStateException("Cannot transition to REFUND_REQUESTED from status: " + this.status);
        }
        this.refundId = refundId;
        this.status = CancellationObligationStatus.REFUND_REQUESTED;
        this.updatedAt = Instant.now();
    }

    public void markRefunded() {
        if (this.status != CancellationObligationStatus.REFUND_REQUESTED 
            && this.status != CancellationObligationStatus.REFUND_DUE
            && this.status != CancellationObligationStatus.REFUND_FAILED) {
            throw new IllegalStateException("Cannot transition to REFUNDED from status: " + this.status);
        }
        this.status = CancellationObligationStatus.REFUNDED;
        this.failureCode = null;
        this.failureMessage = null;
        this.updatedAt = Instant.now();
    }

    public void retryRefund() {
        if (this.status != CancellationObligationStatus.REFUND_FAILED) {
            throw new IllegalStateException("Cannot retry refund from status: " + this.status);
        }
        this.status = CancellationObligationStatus.REFUND_REQUESTED;
        this.failureCode = null;
        this.failureMessage = null;
        this.updatedAt = Instant.now();
    }

    public void markRefundFailed(String failureCode, String failureMessage) {
        if (this.status == CancellationObligationStatus.REFUND_FAILED) {
            this.failureCode = failureCode;
            this.failureMessage = failureMessage;
            this.updatedAt = Instant.now();
            return;
        }
        if (this.status != CancellationObligationStatus.REFUND_REQUESTED && this.status != CancellationObligationStatus.REFUND_DUE) {
            throw new IllegalStateException("Cannot transition to REFUND_FAILED from status: " + this.status);
        }
        this.status = CancellationObligationStatus.REFUND_FAILED;
        this.failureCode = failureCode;
        this.failureMessage = failureMessage;
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getBookingId() { return bookingId; }
    public UUID getPropertyId() { return propertyId; }
    public UUID getCancellationQuoteId() { return cancellationQuoteId; }
    public String getCancellationQuoteFingerprint() { return cancellationQuoteFingerprint; }
    public UUID getPolicyRuleId() { return policyRuleId; }
    public Integer getPolicyRuleVersion() { return policyRuleVersion; }
    public BigDecimal getVerifiedCollectedAmount() { return verifiedCollectedAmount; }
    public BigDecimal getRefundableAmount() { return refundableAmount; }
    public BigDecimal getForfeitedAmount() { return forfeitedAmount; }
    public String getCurrency() { return currency; }
    public String getSpecialRefundReasonCode() { return specialRefundReasonCode; }
    public String getSpecialRefundEvidenceRef() { return specialRefundEvidenceRef; }
    public UUID getRefundId() { return refundId; }
    public CancellationObligationStatus getStatus() { return status; }
    public String getFailureCode() { return failureCode; }
    public String getFailureMessage() { return failureMessage; }
    public UUID getSourceEventId() { return sourceEventId; }
    public String getCorrelationId() { return correlationId; }
    public Long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
