package com.orinan.db.workspace.projection;

import java.time.LocalDateTime;
import com.orinan.db.user.enums.UserStatus;

public record AdminWorkspaceProjection(Long id, String name, Long ownerId, String ownerEmail, UserStatus ownerStatus,
        long memberCount, long connectionCount, LocalDateTime registeredAt, LocalDateTime updatedAt) {}
