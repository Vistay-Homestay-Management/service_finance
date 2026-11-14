package com.vistay.finance.application.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record CancellationPreviewResponse(
    UUID bookingId,
    BigDecimal verifiedCollectedAmount,
    String currency,
    int transactionCount,
    String calculationSnapshotHash,
    List<PaymentTransactionSummaryDto> transactions
) {}
