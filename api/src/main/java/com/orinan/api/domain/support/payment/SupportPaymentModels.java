package com.orinan.api.domain.support.payment;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public final class SupportPaymentModels {
    private SupportPaymentModels() { }

    public record Order(String orderId, long ticketId, String orderName, long amount, String clientKey, String customerKey, String status) { }
    public record Result(String orderId, String status, long amount, long ticketId) { }
    public record Confirm(@NotBlank @Size(max = 200) String paymentKey,
                          @NotBlank @Size(min = 6, max = 64) String orderId, @Positive long amount) {
        @JsonAnySetter public void rejectUnknownField(String field, Object value) {
            throw new IllegalArgumentException("결제 승인에 허용되지 않는 항목입니다.");
        }
        @Override public String toString() { return "SupportPaymentConfirm[REDACTED]"; }
    }
    public record Webhook(boolean received) { }

    record Attempt(String orderId, String paymentKey, long amount, long revision, boolean confirm) {
        @Override public String toString() { return "SupportPaymentAttempt[REDACTED]"; }
    }
}
