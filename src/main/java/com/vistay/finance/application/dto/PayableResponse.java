package com.vistay.finance.application.dto;

import com.vistay.finance.domain.model.Payable;
import com.vistay.finance.domain.model.PayableStatus;
import com.vistay.finance.domain.model.PayeeType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record PayableResponse(
        UUID id,
        String payableCode,
        String sourceKey,
        PayeeType payeeType,
        UUID payeeId,
        UUID propertyId,
        UUID contractId,
        String category,
        String description,
        LocalDate servicePeriodStart,
        LocalDate servicePeriodEnd,
        LocalDate dueDate,
        BigDecimal amountDue,
        BigDecimal amountPaid,
        BigDecimal amountWaived,
        BigDecimal remainingAmount,
        String currency,
        PayableStatus status,
        Instant settledAt,
        Instant createdAt,
        Instant updatedAt
) {
    public static PayableResponse from(Payable payable) {
        return new PayableResponse(
                payable.getId(),
                payable.getPayableCode(),
                payable.getSourceKey(),
                payable.getPayeeType(),
                payable.getPayeeId(),
                payable.getPropertyId(),
                payable.getContractId(),
                payable.getCategory(),
                payable.getDescription(),
                payable.getServicePeriodStart(),
                payable.getServicePeriodEnd(),
                payable.getDueDate(),
                payable.getAmountDue(),
                payable.getAmountPaid(),
                payable.getAmountWaived(),
                payable.getRemainingAmount(),
                payable.getCurrency(),
                payable.getStatus(),
                payable.getSettledAt(),
                payable.getCreatedAt(),
                payable.getUpdatedAt()
        );
    }
}

