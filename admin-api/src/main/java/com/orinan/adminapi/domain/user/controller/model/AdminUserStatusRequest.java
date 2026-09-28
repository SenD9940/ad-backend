package com.orinan.adminapi.domain.user.controller.model;

import com.orinan.db.user.enums.UserStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AdminUserStatusRequest(@NotNull UserStatus status, @Size(max = 500) String reason) {}
