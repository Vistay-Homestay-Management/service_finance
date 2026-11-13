package com.vistay.finance.domain.model;

public enum AmendmentAssessmentStatus {
    PREVIEWED,
    COLLECTION_REQUIRED,
    REFUND_DUE,
    NO_CHANGE,
    WAIVER_REVIEW,
    VERIFIED,
    WAIVED_FINAL,
    STALE,
    EXECUTED;

    public boolean isExecutableFinal() {
        return this == VERIFIED || this == WAIVED_FINAL;
    }
}
