package com.orinan.adminapi.domain.support.controller.model;

import com.orinan.db.support.enums.SupportAccessMode;
import jakarta.validation.constraints.*;

public record AdminSupportTicketRequest(
        @NotNull @Positive Long workspaceId,
        @NotNull @Positive Long customerUserId,
        @NotBlank @Size(max = 150) String title,
        @NotBlank @Size(max = 1000) String description,
        @NotNull SupportAccessMode accessMode,
        @NotNull @Min(0) @Max(1000000000) Long amountKrw,
        @Size(max = 500) String reason) {}
