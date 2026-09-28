package com.orinan.adminapi.domain.workspace.controller.model;

import java.time.LocalDateTime;
import com.orinan.db.user.enums.UserStatus;

public record AdminWorkspaceResponse(Long id, String name, Long ownerId, String ownerEmail, UserStatus ownerStatus,
        long memberCount, long connectionCount, LocalDateTime registeredAt, LocalDateTime updatedAt) {}
