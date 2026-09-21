package com.orinan.api.domain.platformconnection.controller;

import com.orinan.api.annotation.UserSession;
import com.orinan.api.common.api.Api;
import com.orinan.api.domain.platformconnection.business.PlatformConnectionBusiness;
import com.orinan.api.domain.platformconnection.controller.model.MetaAssetSelectRequest;
import com.orinan.api.domain.platformconnection.controller.model.MetaAuthorizeResponse;
import com.orinan.api.domain.platformconnection.controller.model.PlatformAssetResponse;
import com.orinan.api.domain.platformconnection.controller.model.PlatformConnectionResponse;
import com.orinan.api.domain.platformconnection.meta.MetaGraphClient;
import com.orinan.api.domain.platformconnection.meta.MetaProperties;
import com.orinan.api.domain.platformconnection.service.MetaOAuthStateService;
import com.orinan.api.domain.user.controller.model.UserResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/connections")
@RequiredArgsConstructor
public class PlatformConnectionApiController {

    private final PlatformConnectionBusiness business;
    private final MetaProperties properties;

    @GetMapping
    public Api<List<PlatformConnectionResponse>> findAll(
            @UserSession UserResponse user,
            @PathVariable Long workspaceId
    ) {
        return Api.OK(business.findAll(workspaceId, user.getId()));
    }

    @PostMapping("/meta/authorize")
    public ResponseEntity<Api<MetaAuthorizeResponse>> authorizeMeta(
            @UserSession UserResponse user,
            @PathVariable Long workspaceId
    ) {
        var state = business.startMetaAuthorization(workspaceId, user.getId());
        var response = new MetaAuthorizeResponse(business.authorizationUrl(state), MetaOAuthStateService.VALIDITY.toSeconds());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, MetaOAuthCallbackController.stateCookie(
                        properties, state, MetaOAuthStateService.VALIDITY).toString())
                .body(Api.OK(response));
    }

    @GetMapping("/{connectionId}/meta/assets")
    public Api<List<MetaGraphClient.DiscoveredAsset>> discoverMetaAssets(
            @UserSession UserResponse user,
            @PathVariable Long workspaceId,
            @PathVariable Long connectionId
    ) {
        return Api.OK(business.discoverMetaAssets(workspaceId, connectionId, user.getId()));
    }

    @PostMapping("/{connectionId}/meta/assets")
    public Api<List<PlatformAssetResponse>> selectMetaAssets(
            @UserSession UserResponse user,
            @PathVariable Long workspaceId,
            @PathVariable Long connectionId,
            @RequestBody @Valid MetaAssetSelectRequest request
    ) {
        return Api.OK(business.selectMetaAssets(workspaceId, connectionId, user.getId(), request));
    }
}
