package com.orinan.adminapi.domain.user.controller.model;

import com.orinan.db.user.enums.UserStatus;
import com.orinan.db.user.enums.UserRole;
import java.time.LocalDateTime;

public record AdminUserResponse(long id, String email, String name, UserStatus status, UserRole role,
                                LocalDateTime lastLoginAt, LocalDateTime registeredAt,
                                LocalDateTime updatedAt, LocalDateTime unRegisteredAt,
                                long ownedWorkspaceCount, long workspaceCount) {}
