package com.orinan.api.domain.platformconnection.controller.model;

import com.orinan.db.platformconnection.enums.ProviderType;

import java.time.LocalDateTime;
import java.util.List;

public record PlatformConnectionResponse(
        Long id, Long workspaceId, ProviderType providerType,
        String externalAccountId, String accountName, boolean requiresReauth,
        LocalDateTime expiresAt, List<PlatformAssetResponse> assets,
        String connectionMode, String connectionStatus
) {
    public PlatformConnectionResponse(Long id, Long workspaceId, ProviderType providerType,
                                      String externalAccountId, String accountName, boolean requiresReauth,
                                      LocalDateTime expiresAt, List<PlatformAssetResponse> assets) {
        this(id, workspaceId, providerType, externalAccountId, accountName, requiresReauth, expiresAt, assets,
                providerType == ProviderType.NAVER ? "MANUAL" : null,
                providerType == ProviderType.NAVER ? (requiresReauth ? "REAUTH_REQUIRED" : "CONNECTED") : null);
    }
}
