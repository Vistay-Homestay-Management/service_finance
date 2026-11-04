package com.vistay.finance.domain.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
    name = "payment_transactions",
    schema = "app",
    indexes = {
        @Index(name = "ix_payment_transaction_booking", columnList = "booking_id, occurred_at"),
        @Index(name = "ix_payment_transaction_status", columnList = "status, occurred_at"),
        @Index(name = "ux_payment_transaction_external", columnList = "payment_provider, external_transaction_id")
    }
)
public class PaymentTransaction {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "transaction_code", nullable = false, unique = true, length = 60)
    private String transactionCode;

    @Column(name = "payment_request_id")
    private UUID paymentRequestId;

    @Column(name = "payment_attempt_id")
    private UUID paymentAttemptId;

    @Column(name = "original_transaction_id")
    private UUID originalTransactionId;

    @Column(name = "booking_id")
    private UUID bookingId;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false, length = 24)
    private TransactionType transactionType;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "VND";

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false, length = 32)
    private PaymentMethod paymentMethod;

    @Column(name = "payment_account_id")
    private UUID paymentAccountId;

    @Column(name = "payment_provider", length = 80)
    private String paymentProvider;

    @Column(name = "external_transaction_id", length = 180)
    private String externalTransactionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private TransactionStatus status = TransactionStatus.PENDING;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "recorded_by_admin_user_id")
    private UUID recordedByAdminUserId;

    @Column(name = "approved_by_admin_user_id")
    private UUID approvedByAdminUserId;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 160)
    private String idempotencyKey;

    @Column(name = "description", length = 1000)
    private String description;

    @Column(name = "metadata", columnDefinition = "jsonb")
    private String metadata = "{}";

    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected PaymentTransaction() {
    }

    public PaymentTransaction(
        UUID id,
        String transactionCode,
        UUID paymentRequestId,
        UUID paymentAttemptId,
        UUID originalTransactionId,
        UUID bookingId,
        TransactionType transactionType,
        BigDecimal amount,
        String currency,
        PaymentMethod paymentMethod,
        UUID paymentAccountId,
        String paymentProvider,
        String externalTransactionId,
        TransactionStatus status,
        String idempotencyKey,
        String description,
        UUID recordedByAdminUserId
    ) {
        this.id = id != null ? id : UUID.randomUUID();
        this.transactionCode = transactionCode;
        this.paymentRequestId = paymentRequestId;
        this.paymentAttemptId = paymentAttemptId;
        this.originalTransactionId = originalTransactionId;
        this.bookingId = bookingId;
        this.transactionType = transactionType;
        this.amount = amount;
        this.currency = (currency != null && !currency.isBlank()) ? currency : "VND";
        this.paymentMethod = paymentMethod;
        this.paymentAccountId = paymentAccountId;
        this.paymentProvider = paymentProvider;
        this.externalTransactionId = externalTransactionId;
        this.status = status != null ? status : TransactionStatus.PENDING;
        this.idempotencyKey = idempotencyKey;
        this.description = description;
        this.recordedByAdminUserId = recordedByAdminUserId;
        this.occurredAt = Instant.now();
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
        this.metadata = "{}";
    }

    public void markSucceeded() {
        this.status = TransactionStatus.SUCCEEDED;
        this.completedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void markFailed() {
        this.status = TransactionStatus.FAILED;
        this.completedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getTransactionCode() {
        return transactionCode;
    }

    public UUID getPaymentRequestId() {
        return paymentRequestId;
    }

    public UUID getPaymentAttemptId() {
        return paymentAttemptId;
    }

    public UUID getOriginalTransactionId() {
        return originalTransactionId;
    }

    public UUID getBookingId() {
        return bookingId;
    }

    public TransactionType getTransactionType() {
        return transactionType;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public PaymentMethod getPaymentMethod() {
        return paymentMethod;
    }

    public UUID getPaymentAccountId() {
        return paymentAccountId;
    }

    public String getPaymentProvider() {
        return paymentProvider;
    }

    public String getExternalTransactionId() {
        return externalTransactionId;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public UUID getRecordedByAdminUserId() {
        return recordedByAdminUserId;
    }

    public UUID getApprovedByAdminUserId() {
        return approvedByAdminUserId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getDescription() {
        return description;
    }

    public String getMetadata() {
        return metadata;
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
