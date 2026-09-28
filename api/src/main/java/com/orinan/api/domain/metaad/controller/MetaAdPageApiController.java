package com.orinan.api.domain.metaad.controller;

import com.orinan.api.annotation.UserSession;
import com.orinan.api.common.api.Api;
import com.orinan.api.domain.metaad.business.MetaAdPageBusiness;
import com.orinan.api.domain.metaad.controller.model.MetaAdPageSaveRequest;
import com.orinan.api.domain.platformconnection.controller.model.PlatformAssetResponse;
import com.orinan.api.domain.platformconnection.meta.MetaGraphClient;
import com.orinan.api.domain.user.controller.model.UserResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/meta/ad-accounts/{assetId}/pages")
@RequiredArgsConstructor
public class MetaAdPageApiController {

    private final MetaAdPageBusiness business;

    @GetMapping
    public Api<List<MetaGraphClient.DiscoveredAsset>> getPages(
            @UserSession UserResponse user,
            @PathVariable Long workspaceId,
            @PathVariable Long assetId
    ) {
        return Api.OK(business.getPages(workspaceId, assetId, user.getId()));
    }

    @PostMapping
    public Api<List<PlatformAssetResponse>> savePage(
            @UserSession UserResponse user,
            @PathVariable Long workspaceId,
            @PathVariable Long assetId,
            @RequestBody @Valid MetaAdPageSaveRequest request
    ) {
        return Api.OK(business.savePage(workspaceId, assetId, user.getId(), request));
    }
}
