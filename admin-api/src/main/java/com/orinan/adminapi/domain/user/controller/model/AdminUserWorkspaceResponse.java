package com.orinan.adminapi.domain.user.controller.model;

import java.time.LocalDateTime;

public record AdminUserWorkspaceResponse(long id, String name, Long ownerId, String ownerEmail,
                                         String role, LocalDateTime registeredAt, LocalDateTime updatedAt) {}
