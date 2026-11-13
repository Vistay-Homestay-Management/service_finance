package com.vistay.finance.application.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentTransactionSummaryDto(
    UUID transactionId,
    String transactionCode,
    String transactionType,
    BigDecimal amount,
    String currency,
    String paymentMethod,
    String status,
    Instant occurredAt
) {}
