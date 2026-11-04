package com.vistay.finance.domain.model;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "settlement_lines", schema = "app")
public class SettlementLine {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id = UUID.randomUUID();

    @Column(name = "settlement_period_id", nullable = false)
    private UUID settlementPeriodId;

    @Column(name = "booking_id", nullable = false)
    private UUID bookingId;

    @Column(name = "booking_code", nullable = false, length = 40)
    private String bookingCode;

    @Column(name = "check_in_date")
    private LocalDate checkInDate;

    @Column(name = "check_out_date")
    private LocalDate checkOutDate;

    @Column(name = "gross_revenue", nullable = false, precision = 19, scale = 4)
    private BigDecimal grossRevenue = BigDecimal.ZERO;

    @Column(name = "commission_rate", nullable = false, precision = 7, scale = 4)
    private BigDecimal commissionRate = BigDecimal.ZERO;

    @Column(name = "commission_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal commissionAmount = BigDecimal.ZERO;

    @Column(name = "cleaning_fee", nullable = false, precision = 19, scale = 4)
    private BigDecimal cleaningFee = BigDecimal.ZERO;

    @Column(name = "net_payout", nullable = false, precision = 19, scale = 4)
    private BigDecimal netPayout = BigDecimal.ZERO;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3, columnDefinition = "char(3)")
    private String currency = "VND";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected SettlementLine() {}

    public SettlementLine(UUID settlementPeriodId, UUID bookingId, String bookingCode,
                          LocalDate checkInDate, LocalDate checkOutDate,
                          BigDecimal grossRevenue, BigDecimal commissionRate, BigDecimal cleaningFee,
                          String currency) {
        if (settlementPeriodId == null) throw new IllegalArgumentException("settlementPeriodId cannot be null");
        if (bookingId == null) throw new IllegalArgumentException("bookingId cannot be null");
        if (bookingCode == null || bookingCode.isBlank()) throw new IllegalArgumentException("bookingCode cannot be blank");
        if (grossRevenue == null || grossRevenue.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("grossRevenue cannot be negative");
        }
        if (commissionRate == null || commissionRate.compareTo(BigDecimal.ZERO) < 0 || commissionRate.compareTo(new BigDecimal("1.0000")) > 0) {
            throw new IllegalArgumentException("commissionRate must be between 0.0 and 1.0");
        }

        this.settlementPeriodId = settlementPeriodId;
        this.bookingId = bookingId;
        this.bookingCode = bookingCode;
        this.checkInDate = checkInDate;
        this.checkOutDate = checkOutDate;
        this.grossRevenue = grossRevenue;
        this.commissionRate = commissionRate;
        this.cleaningFee = cleaningFee != null ? cleaningFee : BigDecimal.ZERO;
        this.currency = (currency != null && !currency.isBlank()) ? currency : "VND";

        // Calculate commission amount and net payout:
        // commission_amount = gross_revenue * commission_rate
        this.commissionAmount = this.grossRevenue.multiply(this.commissionRate).setScale(4, RoundingMode.HALF_UP);
        // net_payout = gross_revenue - commission_amount + cleaning_fee
        this.netPayout = this.grossRevenue.subtract(this.commissionAmount).add(this.cleaningFee);
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getSettlementPeriodId() { return settlementPeriodId; }
    public UUID getBookingId() { return bookingId; }
    public String getBookingCode() { return bookingCode; }
    public LocalDate getCheckInDate() { return checkInDate; }
    public LocalDate getCheckOutDate() { return checkOutDate; }
    public BigDecimal getGrossRevenue() { return grossRevenue; }
    public BigDecimal getCommissionRate() { return commissionRate; }
    public BigDecimal getCommissionAmount() { return commissionAmount; }
    public BigDecimal getCleaningFee() { return cleaningFee; }
    public BigDecimal getNetPayout() { return netPayout; }
    public String getCurrency() { return currency; }
    public Instant getCreatedAt() { return createdAt; }
}
