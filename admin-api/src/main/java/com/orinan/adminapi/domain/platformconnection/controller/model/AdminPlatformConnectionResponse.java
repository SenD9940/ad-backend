package com.orinan.adminapi.domain.platformconnection.controller.model;

import java.time.LocalDateTime;
import com.orinan.db.platformconnection.enums.ProviderType;

public record AdminPlatformConnectionResponse(Long id, Long workspaceId, String workspaceName, ProviderType provider,
        String externalAccountId, String accountName, Boolean requiresReauth, long assetCount,
        LocalDateTime expiresAt, LocalDateTime registeredAt, LocalDateTime updatedAt) {}
