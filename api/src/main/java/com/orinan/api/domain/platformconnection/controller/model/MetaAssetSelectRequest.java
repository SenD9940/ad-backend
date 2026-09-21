package com.orinan.api.domain.platformconnection.controller.model;

import com.orinan.db.platformasset.enums.AssetType;
import com.orinan.db.platformasset.enums.PlatformType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record MetaAssetSelectRequest(
        @NotEmpty @Size(max = 100) List<@NotNull @Valid Selection> assets
) {
    public record Selection(
            @NotBlank @Size(max = 255) String externalId,
            @NotNull PlatformType platformType,
            @NotNull AssetType assetType
    ) {
    }
}
