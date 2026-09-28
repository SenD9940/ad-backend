package com.orinan.adminapi.domain.audit.controller.model;

import java.time.LocalDateTime;

public record AdminAuditResponse(Long id, Long actorUserId, String action, String targetType, Long targetId,
                                 String reason, String beforeValue, String afterValue, LocalDateTime createdAt) { }
