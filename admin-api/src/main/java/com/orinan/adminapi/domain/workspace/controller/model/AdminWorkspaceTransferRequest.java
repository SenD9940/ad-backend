package com.orinan.adminapi.domain.workspace.controller.model;

import jakarta.validation.constraints.*;

public record AdminWorkspaceTransferRequest(@NotNull @Positive Long expectedOwnerId, @NotNull @Positive Long newOwnerId,
        @Size(max = 500) String reason) {}
