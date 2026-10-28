package com.vistay.finance.domain.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
    name = "payment_requests",
    schema = "app",
    indexes = {
        @Index(name = "ix_payment_request_booking", columnList = "booking_id, created_at"),
        @Index(name = "ix_payment_request_pending", columnList = "status, expires_at")
    }
)
public class PaymentRequest {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "request_code", nullable = false, unique = true, length = 50)
    private String requestCode;

    @Column(name = "booking_id")
    private UUID bookingId;

    @Enumerated(EnumType.STRING)
    @Column(name = "payer_type", nullable = false, length = 24)
    private PayerType payerType;

    @Column(name = "payer_id")
    private UUID payerId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, length = 3, columnDefinition = "char(3)")
    private String currency = "VND";

    @Column(name = "purpose", nullable = false, length = 500)
    private String purpose;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private PaymentRequestStatus status = PaymentRequestStatus.PENDING;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 160)
    private String idempotencyKey;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "created_by_admin_user_id")
    private UUID createdByAdminUserId;

    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected PaymentRequest() {
    }

    public PaymentRequest(
        UUID id,
        String requestCode,
        UUID bookingId,
        PayerType payerType,
        UUID payerId,
        BigDecimal amount,
        String currency,
        String purpose,
        String idempotencyKey,
        Instant expiresAt,
        UUID createdByAdminUserId
    ) {
        this.id = id != null ? id : UUID.randomUUID();
        this.requestCode = requestCode;
        this.bookingId = bookingId;
        this.payerType = payerType;
        this.payerId = payerId;
        this.amount = amount;
        this.currency = (currency != null && !currency.isBlank()) ? currency : "VND";
        this.purpose = purpose;
        this.idempotencyKey = idempotencyKey;
        this.expiresAt = expiresAt;
        this.createdByAdminUserId = createdByAdminUserId;
        this.status = PaymentRequestStatus.PENDING;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = Instant.now();
    }

    public void markProcessing() {
        this.status = PaymentRequestStatus.PROCESSING;
        this.updatedAt = Instant.now();
    }

    public void markPaid() {
        this.status = PaymentRequestStatus.PAID;
        this.updatedAt = Instant.now();
    }

    public void markPartiallyPaid() {
        this.status = PaymentRequestStatus.PARTIALLY_PAID;
        this.updatedAt = Instant.now();
    }

    public void markCancelled() {
        this.status = PaymentRequestStatus.CANCELLED;
        this.updatedAt = Instant.now();
    }

    public void markFailed() {
        this.status = PaymentRequestStatus.FAILED;
        this.updatedAt = Instant.now();
    }

    public void markExpired() {
        this.status = PaymentRequestStatus.EXPIRED;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getRequestCode() {
        return requestCode;
    }

    public UUID getBookingId() {
        return bookingId;
    }

    public PayerType getPayerType() {
        return payerType;
    }

    public UUID getPayerId() {
        return payerId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getPurpose() {
        return purpose;
    }

    public PaymentRequestStatus getStatus() {
        return status;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public UUID getCreatedByAdminUserId() {
        return createdByAdminUserId;
    }

    public Long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
