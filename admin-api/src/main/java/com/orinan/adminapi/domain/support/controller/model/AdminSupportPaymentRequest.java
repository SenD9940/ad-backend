package com.orinan.adminapi.domain.support.controller.model;

import com.orinan.db.support.enums.SupportPaymentStatus;
import jakarta.validation.constraints.*;

public record AdminSupportPaymentRequest(
        @NotNull SupportPaymentStatus paymentStatus,
        @Size(max = 200) String paymentReference,
        @Size(max = 500) String reason) {}
