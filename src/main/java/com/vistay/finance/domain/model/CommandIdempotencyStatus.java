package com.vistay.finance.domain.model;

public enum CommandIdempotencyStatus {
    PROCESSING,
    SUCCEEDED,
    FAILED
}
