package com.vistay.finance.domain.model;

public enum PaymentRequestStatus {
    PENDING,
    PROCESSING,
    PARTIALLY_PAID,
    PAID,
    EXPIRED,
    CANCELLED,
    FAILED
}
