package com.orinan.api.domain.support.payment;

/** Contains only a safe message and classification, never the upstream body or request. */
public class TossPaymentException extends RuntimeException {
    private final boolean ambiguous;
    private final Integer httpStatus;

    public TossPaymentException(String message, boolean ambiguous, Integer httpStatus) {
        super(message);
        this.ambiguous = ambiguous;
        this.httpStatus = httpStatus;
    }

    public boolean isAmbiguous() { return ambiguous; }
    public Integer getHttpStatus() { return httpStatus; }
}
