package com.orinan.adminapi.domain.user.controller.model;

import com.orinan.db.user.enums.UserRole;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AdminUserRoleRequest(@NotNull UserRole role, @Size(max = 500) String reason) {}
