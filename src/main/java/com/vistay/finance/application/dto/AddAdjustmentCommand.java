package com.vistay.finance.application.dto;

import com.vistay.finance.domain.model.AdjustmentType;

import java.math.BigDecimal;
import java.util.UUID;

public record AddAdjustmentCommand(
    AdjustmentType adjustmentType,
    BigDecimal amount,
    String currency,
    String reason,
    UUID createdByAdminUserId
) {}

