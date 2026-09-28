package com.orinan.adminapi.domain.support.controller.model;

import com.orinan.db.support.enums.SupportAccessMode;
import java.time.LocalDateTime;

/** Returned once on issuance, never persisted or exposed by list/detail endpoints. */
public record AdminSupportSessionTokenResponse(long sessionId, long ticketId, long workspaceId,
        long customerUserId, SupportAccessMode accessMode, String accessToken, LocalDateTime expiresAt) {
    @Override
    public String toString() {
        return "AdminSupportSessionTokenResponse[redacted]";
    }
}
