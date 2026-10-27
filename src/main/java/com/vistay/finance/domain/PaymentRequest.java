package com.vistay.finance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "payment_requests", schema = "app")
public class PaymentRequest {
    @Id private UUID id;
    @Column(name = "request_code", nullable = false, length = 50) private String requestCode;
    @Column(name = "booking_id") private UUID bookingId;
    @Column(name = "payer_type", nullable = false, length = 24) private String payerType;
    @Column(name = "payer_id") private UUID payerId;
    @Column(nullable = false, precision = 19, scale = 4) private BigDecimal amount;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3, columnDefinition = "char(3)") private String currency;
    @Column(nullable = false, length = 500) private String purpose;
    @Column(nullable = false, length = 24) private String status;
    @Column(name = "expires_at") private Instant expiresAt;
    @Column(nullable = false) private long version;

    protected PaymentRequest() {}

    public UUID getId() { return id; }
    public String getRequestCode() { return requestCode; }
    public UUID getBookingId() { return bookingId; }
    public String getPayerType() { return payerType; }
    public UUID getPayerId() { return payerId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getPurpose() { return purpose; }
    public String getStatus() { return status; }
    public Instant getExpiresAt() { return expiresAt; }
    public long getVersion() { return version; }
}
