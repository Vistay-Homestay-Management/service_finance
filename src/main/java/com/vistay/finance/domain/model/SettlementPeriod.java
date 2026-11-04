package com.vistay.finance.domain.model;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "settlement_periods", schema = "app")
public class SettlementPeriod {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id = UUID.randomUUID();

    @Column(name = "period_code", nullable = false, unique = true, length = 60)
    private String periodCode;

    @Column(name = "host_id", nullable = false)
    private UUID hostId;

    @Column(name = "property_id")
    private UUID propertyId;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(name = "total_gross_revenue", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalGrossRevenue = BigDecimal.ZERO;

    @Column(name = "total_commission", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalCommission = BigDecimal.ZERO;

    @Column(name = "total_cleaning_fees", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalCleaningFees = BigDecimal.ZERO;

    @Column(name = "total_adjustments", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalAdjustments = BigDecimal.ZERO;

    @Column(name = "total_net_payout", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalNetPayout = BigDecimal.ZERO;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3, columnDefinition = "char(3)")
    private String currency = "VND";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private SettlementStatus status = SettlementStatus.OPEN;

    @Column(name = "opened_by_admin_user_id")
    private UUID openedByAdminUserId;

    @Column(name = "approved_by_admin_user_id")
    private UUID approvedByAdminUserId;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(length = 2000)
    private String notes;

    @Column(name = "payable_id")
    private UUID payableId;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected SettlementPeriod() {}

    public SettlementPeriod(String periodCode, UUID hostId, UUID propertyId, LocalDate startDate,
                            LocalDate endDate, LocalDate dueDate, String currency, UUID openedByAdminUserId, String notes) {
        this(UUID.randomUUID(), periodCode, hostId, propertyId, startDate, endDate, dueDate, currency, openedByAdminUserId, notes);
    }

    public SettlementPeriod(UUID id, String periodCode, UUID hostId, UUID propertyId, LocalDate startDate,
                            LocalDate endDate, LocalDate dueDate, String currency, UUID openedByAdminUserId, String notes) {
        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("End date cannot be before start date");
        }
        if (dueDate.isBefore(endDate)) {
            throw new IllegalArgumentException("Due date cannot be before end date");
        }
        this.id = id != null ? id : UUID.randomUUID();
        this.periodCode = periodCode;
        this.hostId = hostId;
        this.propertyId = propertyId;
        this.startDate = startDate;
        this.endDate = endDate;
        this.dueDate = dueDate;
        this.currency = (currency != null && !currency.isBlank()) ? currency : "VND";
        this.openedByAdminUserId = openedByAdminUserId;
        this.notes = notes;
        this.status = SettlementStatus.OPEN;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void updateTotals(BigDecimal grossRevenue, BigDecimal commission, BigDecimal cleaningFees, BigDecimal adjustments) {
        this.totalGrossRevenue = grossRevenue != null ? grossRevenue : BigDecimal.ZERO;
        this.totalCommission = commission != null ? commission : BigDecimal.ZERO;
        this.totalCleaningFees = cleaningFees != null ? cleaningFees : BigDecimal.ZERO;
        this.totalAdjustments = adjustments != null ? adjustments : BigDecimal.ZERO;
        this.totalNetPayout = this.totalGrossRevenue
                .subtract(this.totalCommission)
                .add(this.totalCleaningFees)
                .add(this.totalAdjustments); // Note: negative adjustment reduces payout
        this.status = SettlementStatus.CALCULATED;
        this.updatedAt = Instant.now();
    }

    public void submitForApproval() {
        if (this.status != SettlementStatus.CALCULATED && this.status != SettlementStatus.REOPENED) {
            throw new IllegalStateException("Only CALCULATED or REOPENED period can be submitted for approval");
        }
        this.status = SettlementStatus.PENDING_APPROVAL;
        this.updatedAt = Instant.now();
    }

    public void approve(UUID approverAdminUserId) {
        if (this.status != SettlementStatus.PENDING_APPROVAL) {
            throw new IllegalStateException("Period is not in PENDING_APPROVAL status");
        }
        if (approverAdminUserId == null) {
            throw new IllegalArgumentException("Approver user ID cannot be null");
        }
        if (approverAdminUserId.equals(this.openedByAdminUserId)) {
            throw new IllegalStateException("Maker-checker violation: Creator cannot approve own settlement period");
        }
        this.approvedByAdminUserId = approverAdminUserId;
        this.approvedAt = Instant.now();
        this.status = SettlementStatus.APPROVED;
        this.updatedAt = Instant.now();
    }

    public void reject(UUID rejecterAdminUserId, String reason) {
        if (this.status != SettlementStatus.PENDING_APPROVAL) {
            throw new IllegalStateException("Period is not in PENDING_APPROVAL status");
        }
        this.status = SettlementStatus.REJECTED;
        this.notes = (this.notes != null ? this.notes + " | " : "") + "Rejected by " + rejecterAdminUserId + ": " + reason;
        this.updatedAt = Instant.now();
    }

    public void reopen(UUID reopenedByAdminUserId, String reason) {
        if (this.status != SettlementStatus.REJECTED && this.status != SettlementStatus.APPROVED) {
            throw new IllegalStateException("Only REJECTED or APPROVED period can be reopened");
        }
        this.status = SettlementStatus.REOPENED;
        this.approvedByAdminUserId = null;
        this.approvedAt = null;
        this.notes = (this.notes != null ? this.notes + " | " : "") + "Reopened by " + reopenedByAdminUserId + ": " + reason;
        this.updatedAt = Instant.now();
    }

    public void markPayableCreated(UUID payableId) {
        this.payableId = payableId;
        this.status = SettlementStatus.PAYABLE_CREATED;
        this.updatedAt = Instant.now();
    }

    public void markPaid() {
        if (this.status != SettlementStatus.PAYABLE_CREATED && this.status != SettlementStatus.APPROVED) {
            throw new IllegalStateException("Only PAYABLE_CREATED or APPROVED period can be marked as PAID");
        }
        this.status = SettlementStatus.PAID;
        this.updatedAt = Instant.now();
    }

    // Getters
    public UUID getId() { return id; }
    public String getPeriodCode() { return periodCode; }
    public UUID getHostId() { return hostId; }
    public UUID getPropertyId() { return propertyId; }
    public LocalDate getStartDate() { return startDate; }
    public LocalDate getEndDate() { return endDate; }
    public LocalDate getDueDate() { return dueDate; }
    public BigDecimal getTotalGrossRevenue() { return totalGrossRevenue; }
    public BigDecimal getTotalCommission() { return totalCommission; }
    public BigDecimal getTotalCleaningFees() { return totalCleaningFees; }
    public BigDecimal getTotalAdjustments() { return totalAdjustments; }
    public BigDecimal getTotalNetPayout() { return totalNetPayout; }
    public String getCurrency() { return currency; }
    public SettlementStatus getStatus() { return status; }
    public UUID getOpenedByAdminUserId() { return openedByAdminUserId; }
    public UUID getApprovedByAdminUserId() { return approvedByAdminUserId; }
    public Instant getApprovedAt() { return approvedAt; }
    public String getNotes() { return notes; }
    public UUID getPayableId() { return payableId; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
