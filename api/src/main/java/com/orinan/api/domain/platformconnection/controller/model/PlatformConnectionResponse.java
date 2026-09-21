package com.orinan.api.domain.platformconnection.controller.model;

import com.orinan.db.platformconnection.enums.ProviderType;

import java.time.LocalDateTime;
import java.util.List;

public record PlatformConnectionResponse(
        Long id, Long workspaceId, ProviderType providerType,
        String externalAccountId, String accountName, boolean requiresReauth,
        LocalDateTime expiresAt, List<PlatformAssetResponse> assets
) {
}
