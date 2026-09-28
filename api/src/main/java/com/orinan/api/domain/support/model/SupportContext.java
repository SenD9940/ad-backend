package com.orinan.api.domain.support.model;

import java.time.LocalDateTime;

/** Authenticated support identity; never carries the bearer secret. */
public record SupportContext(long sessionId, long ticketId, long workspaceId, long adminUserId,
                             long customerUserId, String accessMode, LocalDateTime expiresAt) {
    public static final String REQUEST_ATTRIBUTE = SupportContext.class.getName();
}
