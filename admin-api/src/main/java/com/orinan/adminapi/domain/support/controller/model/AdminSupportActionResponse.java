package com.orinan.adminapi.domain.support.controller.model;

import java.time.LocalDateTime;

public record AdminSupportActionResponse(long id, long sessionId, long ticketId, long actorUserId,
        long customerUserId, long workspaceId, String httpMethod, String path, Integer statusCode,
        LocalDateTime startedAt, LocalDateTime completedAt) {}
