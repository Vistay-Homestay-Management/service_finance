package com.vistay.finance.application.service;

import org.springframework.http.HttpStatus;

/** Stable, non-leaking exception for reservation amendment financial assessment failures. */
public class ReservationAmendmentException extends RuntimeException {
    private final HttpStatus status;
    private final String errorCode;

    public ReservationAmendmentException(HttpStatus status, String errorCode, String message) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
