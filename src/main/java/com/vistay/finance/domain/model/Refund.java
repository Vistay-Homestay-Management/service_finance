package com.vistay.finance.domain.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
    name = "refunds",
    schema = "app",
    indexes = {
        @Index(name = "ix_refund_booking", columnList = "booking_id, requested_at")
    }
)
public class Refund {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "refund_code", nullable = false, unique = true, length = 60)
    private String refundCode;

    @Column(name = "booking_id")
    private UUID bookingId;

    @Column(name = "original_transaction_id", nullable = false)
    private UUID originalTransactionId;

    @Column(name = "refund_transaction_id", unique = true)
    private UUID refundTransactionId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "VND";

    @Column(name = "reason", nullable = false, length = 1000)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private RefundStatus status = RefundStatus.REQUESTED;

    @Column(name = "requested_by_admin_user_id", nullable = false)
    private UUID requestedByAdminUserId;

    @Column(name = "approved_by_admin_user_id")
    private UUID approvedByAdminUserId;

    @Column(name = "external_refund_id", length = 180)
    private String externalRefundId;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 160)
    private String idempotencyKey;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt = Instant.now();

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;

    protected Refund() {
    }

    public Refund(
        UUID id,
        String refundCode,
        UUID bookingId,
        UUID originalTransactionId,
        BigDecimal amount,
        String currency,
        String reason,
        UUID requestedByAdminUserId,
        String idempotencyKey
    ) {
        this.id = id != null ? id : UUID.randomUUID();
        this.refundCode = refundCode;
        this.bookingId = bookingId;
        this.originalTransactionId = originalTransactionId;
        this.amount = amount;
        this.currency = (currency != null && !currency.isBlank()) ? currency : "VND";
        this.reason = reason;
        this.requestedByAdminUserId = requestedByAdminUserId;
        this.idempotencyKey = idempotencyKey;
        this.status = RefundStatus.REQUESTED;
        this.requestedAt = Instant.now();
    }

    public void approve(UUID approvedByAdminUserId) {
        this.status = RefundStatus.APPROVED;
        this.approvedByAdminUserId = approvedByAdminUserId;
        this.approvedAt = Instant.now();
    }

    public void markCompleted(UUID refundTransactionId) {
        this.status = RefundStatus.COMPLETED;
        this.refundTransactionId = refundTransactionId;
        this.completedAt = Instant.now();
    }

    public void markRejected() {
        this.status = RefundStatus.REJECTED;
        this.completedAt = Instant.now();
    }

    public void markFailed() {
        this.status = RefundStatus.FAILED;
        this.completedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getRefundCode() {
        return refundCode;
    }

    public UUID getBookingId() {
        return bookingId;
    }

    public UUID getOriginalTransactionId() {
        return originalTransactionId;
    }

    public UUID getRefundTransactionId() {
        return refundTransactionId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getReason() {
        return reason;
    }

    public RefundStatus getStatus() {
        return status;
    }

    public UUID getRequestedByAdminUserId() {
        return requestedByAdminUserId;
    }

    public UUID getApprovedByAdminUserId() {
        return approvedByAdminUserId;
    }

    public String getExternalRefundId() {
        return externalRefundId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Long getVersion() {
        return version;
    }
}
