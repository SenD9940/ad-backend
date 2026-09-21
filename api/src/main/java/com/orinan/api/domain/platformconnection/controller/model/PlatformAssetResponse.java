package com.orinan.api.domain.platformconnection.controller.model;

import com.orinan.db.platformasset.enums.AssetType;
import com.orinan.db.platformasset.enums.PlatformType;

public record PlatformAssetResponse(
        Long id, String externalId, String name,
        PlatformType platformType, AssetType assetType, String facebookPageId
) {
}
