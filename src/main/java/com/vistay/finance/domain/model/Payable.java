package com.vistay.finance.domain.model;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "payables", schema = "app")
public class Payable {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id = UUID.randomUUID();

    @Column(name = "payable_code", nullable = false, unique = true, length = 60)
    private String payableCode;

    @Column(name = "source_key", nullable = false, unique = true, length = 180)
    private String sourceKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "payee_type", nullable = false, length = 24)
    private PayeeType payeeType;

    @Column(name = "payee_id", nullable = false)
    private UUID payeeId;

    @Column(name = "property_id")
    private UUID propertyId;

    @Column(name = "contract_id")
    private UUID contractId;

    @Column(name = "contract_property_term_id")
    private UUID contractPropertyTermId;

    @Column(name = "work_order_id")
    private UUID workOrderId;

    @Column(name = "category", nullable = false, length = 40)
    private String category;

    @Column(name = "description", nullable = false, length = 500)
    private String description;

    @Column(name = "service_period_start")
    private LocalDate servicePeriodStart;

    @Column(name = "service_period_end")
    private LocalDate servicePeriodEnd;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(name = "amount_due", nullable = false, precision = 19, scale = 4)
    private BigDecimal amountDue;

    @Column(name = "amount_paid", nullable = false, precision = 19, scale = 4)
    private BigDecimal amountPaid = BigDecimal.ZERO;

    @Column(name = "amount_waived", nullable = false, precision = 19, scale = 4)
    private BigDecimal amountWaived = BigDecimal.ZERO;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3, columnDefinition = "char(3)")
    private String currency = "VND";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private PayableStatus status = PayableStatus.OPEN;

    @Column(name = "settled_at")
    private Instant settledAt;

    @Column(name = "contract_term_snapshot", nullable = false, columnDefinition = "jsonb")
    private String contractTermSnapshot = "{}";

    @Column(name = "metadata", nullable = false, columnDefinition = "jsonb")
    private String metadata = "{}";

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected Payable() {}

    public Payable(String payableCode, String sourceKey, PayeeType payeeType, UUID payeeId,
                   UUID propertyId, UUID contractId, String category, String description,
                   LocalDate servicePeriodStart, LocalDate servicePeriodEnd, LocalDate dueDate,
                   BigDecimal amountDue, String currency) {
        this(UUID.randomUUID(), payableCode, sourceKey, payeeType, payeeId, propertyId, contractId,
                category, description, servicePeriodStart, servicePeriodEnd, dueDate, amountDue, currency);
    }

    public Payable(UUID id, String payableCode, String sourceKey, PayeeType payeeType, UUID payeeId,
                   UUID propertyId, UUID contractId, String category, String description,
                   LocalDate servicePeriodStart, LocalDate servicePeriodEnd, LocalDate dueDate,
                   BigDecimal amountDue, String currency) {
        if (propertyId == null && contractId == null) {
            throw new IllegalArgumentException("At least one of propertyId or contractId must not be null");
        }
        if (amountDue == null || amountDue.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Amount due must be non-negative");
        }
        this.id = id != null ? id : UUID.randomUUID();
        this.payableCode = payableCode;
        this.sourceKey = sourceKey;
        this.payeeType = payeeType != null ? payeeType : PayeeType.HOST;
        this.payeeId = payeeId;
        this.propertyId = propertyId;
        this.contractId = contractId;
        this.category = category;
        this.description = description;
        this.servicePeriodStart = servicePeriodStart;
        this.servicePeriodEnd = servicePeriodEnd;
        this.dueDate = dueDate;
        this.amountDue = amountDue;
        this.amountPaid = BigDecimal.ZERO;
        this.amountWaived = BigDecimal.ZERO;
        this.currency = (currency != null && !currency.isBlank()) ? currency : "VND";
        this.status = PayableStatus.OPEN;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void recordPayment(BigDecimal paymentAmount) {
        if (paymentAmount == null || paymentAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Payment amount must be greater than zero");
        }
        if (this.status == PayableStatus.SETTLED || this.status == PayableStatus.CANCELLED) {
            throw new IllegalStateException("Cannot record payment on " + this.status + " payable");
        }

        BigDecimal newAmountPaid = this.amountPaid.add(paymentAmount);
        if (newAmountPaid.add(this.amountWaived).compareTo(this.amountDue) > 0) {
            throw new IllegalArgumentException("Total paid plus waived (" + newAmountPaid.add(this.amountWaived)
                    + ") cannot exceed amount due (" + this.amountDue + ")");
        }

        this.amountPaid = newAmountPaid;
        if (this.amountPaid.add(this.amountWaived).compareTo(this.amountDue) >= 0) {
            this.status = PayableStatus.SETTLED;
            this.settledAt = Instant.now();
        } else {
            this.status = PayableStatus.PARTIALLY_PAID;
        }
        this.updatedAt = Instant.now();
    }

    public void waive(BigDecimal waiveAmount) {
        if (waiveAmount == null || waiveAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Waive amount must be greater than zero");
        }
        BigDecimal newAmountWaived = this.amountWaived.add(waiveAmount);
        if (this.amountPaid.add(newAmountWaived).compareTo(this.amountDue) > 0) {
            throw new IllegalArgumentException("Total paid plus waived cannot exceed amount due");
        }
        this.amountWaived = newAmountWaived;
        if (this.amountPaid.add(this.amountWaived).compareTo(this.amountDue) >= 0) {
            this.status = PayableStatus.SETTLED;
            this.settledAt = Instant.now();
        }
        this.updatedAt = Instant.now();
    }

    public BigDecimal getRemainingAmount() {
        return this.amountDue.subtract(this.amountPaid).subtract(this.amountWaived);
    }

    // Getters and Setters
    public UUID getId() { return id; }
    public String getPayableCode() { return payableCode; }
    public String getSourceKey() { return sourceKey; }
    public PayeeType getPayeeType() { return payeeType; }
    public UUID getPayeeId() { return payeeId; }
    public UUID getPropertyId() { return propertyId; }
    public UUID getContractId() { return contractId; }
    public UUID getContractPropertyTermId() { return contractPropertyTermId; }
    public UUID getWorkOrderId() { return workOrderId; }
    public String getCategory() { return category; }
    public String getDescription() { return description; }
    public LocalDate getServicePeriodStart() { return servicePeriodStart; }
    public LocalDate getServicePeriodEnd() { return servicePeriodEnd; }
    public LocalDate getDueDate() { return dueDate; }
    public BigDecimal getAmountDue() { return amountDue; }
    public BigDecimal getAmountPaid() { return amountPaid; }
    public BigDecimal getAmountWaived() { return amountWaived; }
    public String getCurrency() { return currency; }
    public PayableStatus getStatus() { return status; }
    public Instant getSettledAt() { return settledAt; }
    public String getContractTermSnapshot() { return contractTermSnapshot; }
    public void setContractTermSnapshot(String contractTermSnapshot) { this.contractTermSnapshot = contractTermSnapshot; }
    public String getMetadata() { return metadata; }
    public void setMetadata(String metadata) { this.metadata = metadata; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}

