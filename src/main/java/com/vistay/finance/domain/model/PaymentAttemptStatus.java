package com.vistay.finance.domain.model;

public enum PaymentAttemptStatus {
    CREATED,
    PENDING,
    AUTHORIZED,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    EXPIRED
}
