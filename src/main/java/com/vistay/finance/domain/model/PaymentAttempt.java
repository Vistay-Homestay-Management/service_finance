package com.vistay.finance.domain.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
    name = "payment_attempts",
    schema = "app",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_payment_attempt_number", columnNames = {"payment_request_id", "attempt_number"})
    },
    indexes = {
        @Index(name = "ux_payment_attempt_external", columnList = "payment_provider, external_transaction_id")
    }
)
public class PaymentAttempt {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_request_id", nullable = false)
    private PaymentRequest paymentRequest;

    @Column(name = "attempt_number", nullable = false)
    private Integer attemptNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false, length = 32)
    private PaymentMethod paymentMethod;

    @Column(name = "payment_provider", length = 80)
    private String paymentProvider;

    @Column(name = "external_transaction_id", length = 180)
    private String externalTransactionId;

    @Column(name = "requested_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal requestedAmount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private PaymentAttemptStatus status = PaymentAttemptStatus.CREATED;

    @Column(name = "failure_code", length = 100)
    private String failureCode;

    @Column(name = "failure_message", length = 1000)
    private String failureMessage;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "metadata", columnDefinition = "jsonb")
    private String metadata = "{}";

    protected PaymentAttempt() {
    }

    public PaymentAttempt(
        UUID id,
        PaymentRequest paymentRequest,
        Integer attemptNumber,
        PaymentMethod paymentMethod,
        String paymentProvider,
        String externalTransactionId,
        BigDecimal requestedAmount,
        String currency
    ) {
        this.id = id != null ? id : UUID.randomUUID();
        this.paymentRequest = paymentRequest;
        this.attemptNumber = attemptNumber;
        this.paymentMethod = paymentMethod;
        this.paymentProvider = paymentProvider;
        this.externalTransactionId = externalTransactionId;
        this.requestedAmount = requestedAmount;
        this.currency = currency;
        this.status = PaymentAttemptStatus.CREATED;
        this.requestedAt = Instant.now();
        this.metadata = "{}";
    }

    public void markSucceeded(String externalTransactionId) {
        this.status = PaymentAttemptStatus.SUCCEEDED;
        if (externalTransactionId != null) {
            this.externalTransactionId = externalTransactionId;
        }
        this.completedAt = Instant.now();
    }

    public void markFailed(String code, String message) {
        this.status = PaymentAttemptStatus.FAILED;
        this.failureCode = code;
        this.failureMessage = message;
        this.completedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public PaymentRequest getPaymentRequest() {
        return paymentRequest;
    }

    public Integer getAttemptNumber() {
        return attemptNumber;
    }

    public PaymentMethod getPaymentMethod() {
        return paymentMethod;
    }

    public String getPaymentProvider() {
        return paymentProvider;
    }

    public String getExternalTransactionId() {
        return externalTransactionId;
    }

    public BigDecimal getRequestedAmount() {
        return requestedAmount;
    }

    public String getCurrency() {
        return currency;
    }

    public PaymentAttemptStatus getStatus() {
        return status;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public String getFailureMessage() {
        return failureMessage;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public String getMetadata() {
        return metadata;
    }

    public void setMetadata(String metadata) {
        this.metadata = metadata;
    }
}
