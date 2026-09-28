package com.orinan.db.platformconnection.projection;

import java.time.LocalDateTime;
import com.orinan.db.platformconnection.enums.ProviderType;

public record AdminPlatformConnectionProjection(Long id, Long workspaceId, String workspaceName, ProviderType provider,
        String externalAccountId, String accountName, Boolean requiresReauth, long assetCount,
        LocalDateTime expiresAt, LocalDateTime registeredAt, LocalDateTime updatedAt) {}
