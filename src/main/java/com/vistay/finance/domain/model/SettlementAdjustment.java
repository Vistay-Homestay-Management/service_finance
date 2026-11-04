package com.vistay.finance.domain.model;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "settlement_adjustments", schema = "app")
public class SettlementAdjustment {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id = UUID.randomUUID();

    @Column(name = "settlement_period_id", nullable = false)
    private UUID settlementPeriodId;

    @Enumerated(EnumType.STRING)
    @Column(name = "adjustment_type", nullable = false, length = 40)
    private AdjustmentType adjustmentType;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount = BigDecimal.ZERO;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3, columnDefinition = "char(3)")
    private String currency = "VND";

    @Column(nullable = false, length = 1000)
    private String reason;

    @Column(name = "created_by_admin_user_id", nullable = false)
    private UUID createdByAdminUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected SettlementAdjustment() {}

    public SettlementAdjustment(UUID settlementPeriodId, AdjustmentType adjustmentType,
                                BigDecimal amount, String currency, String reason,
                                UUID createdByAdminUserId) {
        if (settlementPeriodId == null) throw new IllegalArgumentException("settlementPeriodId cannot be null");
        if (adjustmentType == null) throw new IllegalArgumentException("adjustmentType cannot be null");
        if (amount == null) throw new IllegalArgumentException("amount cannot be null");
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("reason cannot be blank");
        if (createdByAdminUserId == null) throw new IllegalArgumentException("createdByAdminUserId cannot be null");

        this.settlementPeriodId = settlementPeriodId;
        this.adjustmentType = adjustmentType;
        this.amount = amount;
        this.currency = (currency != null && !currency.isBlank()) ? currency : "VND";
        this.reason = reason;
        this.createdByAdminUserId = createdByAdminUserId;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getSettlementPeriodId() { return settlementPeriodId; }
    public AdjustmentType getAdjustmentType() { return adjustmentType; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getReason() { return reason; }
    public UUID getCreatedByAdminUserId() { return createdByAdminUserId; }
    public Instant getCreatedAt() { return createdAt; }
}
