package com.orinan.adminapi.domain.support.controller.model;

import com.orinan.db.support.enums.SupportAccessMode;
import java.time.LocalDateTime;

/** Credentials and authentication-version snapshots are deliberately excluded. */
public record AdminSupportSessionResponse(long id, long ticketId, long adminUserId, long customerUserId,
        long workspaceId, SupportAccessMode accessMode, LocalDateTime startedAt, LocalDateTime expiresAt,
        LocalDateTime endedAt, boolean active) {}
