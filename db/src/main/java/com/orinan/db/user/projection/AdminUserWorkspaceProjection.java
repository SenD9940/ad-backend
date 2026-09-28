package com.orinan.db.user.projection;

import java.time.LocalDateTime;

public record AdminUserWorkspaceProjection(long id, String name, Long ownerId, String ownerEmail,
                                         String role, LocalDateTime registeredAt, LocalDateTime updatedAt) {}
