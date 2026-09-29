package com.orinan.api.domain.navercommerce.controller.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

public record NaverOrderActionRequest(
        @NotBlank @Pattern(regexp = "CONFIRM|DISPATCH|APPROVE_CANCEL|APPROVE_RETURN") String action,
        @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String expectedVersion,
        @NotBlank @Pattern(regexp = "[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}") String requestId,
        @Size(max = 40) String deliveryMethod, @Size(max = 50) String deliveryCompanyCode,
        @Size(max = 100) String trackingNumber, OffsetDateTime dispatchDate, Boolean returnReceived) {
    @Override public String toString() { return "OrderActionRequest[REDACTED]"; }
}
