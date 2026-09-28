package com.orinan.db.user.projection;

import com.orinan.db.user.enums.UserStatus;
import com.orinan.db.user.enums.UserRole;
import java.time.LocalDateTime;

public record AdminUserSummaryProjection(long id, String email, String name, UserStatus status, UserRole role,
                                LocalDateTime lastLoginAt, LocalDateTime registeredAt,
                                LocalDateTime updatedAt, LocalDateTime unRegisteredAt,
                                long ownedWorkspaceCount, long workspaceCount) {}
