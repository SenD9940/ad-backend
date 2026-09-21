package com.orinan.api.domain.platformconnection.business;

import com.orinan.api.annotation.Business;
import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.platformconnection.controller.model.MetaAssetSelectRequest;
import com.orinan.api.domain.platformconnection.controller.model.PlatformAssetResponse;
import com.orinan.api.domain.platformconnection.controller.model.PlatformConnectionResponse;
import com.orinan.api.domain.platformconnection.meta.MetaGraphClient;
import com.orinan.api.domain.platformconnection.meta.MetaProperties;
import com.orinan.api.domain.platformconnection.service.MetaOAuthStateService;
import com.orinan.api.domain.platformconnection.service.PlatformConnectionService;
import lombok.RequiredArgsConstructor;

import java.util.List;

@Business
@RequiredArgsConstructor
public class PlatformConnectionBusiness {

    private final PlatformConnectionService service;
    private final MetaGraphClient metaClient;
    private final MetaOAuthStateService states;
    private final MetaProperties properties;

    public String startMetaAuthorization(Long workspaceId, Long userId) {
        service.requireOwner(workspaceId, userId);
        properties.validate();
        return states.issue(workspaceId, userId);
    }

    public String authorizationUrl(String state) {
        return metaClient.authorizationUrl(state);
    }

    public PlatformConnectionResponse completeMetaAuthorization(String state, String browserState,
                                                                String code, String error) {
        var owner = states.consume(state, browserState);
        if (error != null || code == null || code.isBlank() || code.length() > 4096) {
            throw new ApiException(ApiCode.BAD_REQUEST, "Meta 연결이 취소되었거나 인증 코드가 없습니다.");
        }
        service.requireOwner(owner.workspaceId(), owner.userId());
        var account = metaClient.exchangeCode(code);
        return service.saveMetaConnection(owner.workspaceId(), owner.userId(), account);
    }

    public List<PlatformConnectionResponse> findAll(Long workspaceId, Long userId) {
        return service.findAll(workspaceId, userId);
    }

    public List<MetaGraphClient.DiscoveredAsset> discoverMetaAssets(Long workspaceId, Long connectionId, Long userId) {
        var connection = service.getMetaConnection(workspaceId, connectionId, userId);
        var available = metaClient.discoverAssets(connection.getAccessToken());
        // A member may have been removed while the external request was in flight.
        service.requireMember(workspaceId, userId);
        return available;
    }

    public List<PlatformAssetResponse> selectMetaAssets(Long workspaceId, Long connectionId, Long userId,
                                                       MetaAssetSelectRequest request) {
        var connection = service.getMetaConnection(workspaceId, connectionId, userId);
        var available = metaClient.discoverAssets(connection.getAccessToken());
        // Look up every selection remotely. Names and Page relationships never come from the caller.
        var selected = request.assets().stream().distinct().map(selection -> available.stream()
                .filter(asset -> asset.externalId().equals(selection.externalId())
                        && asset.platformType() == selection.platformType()
                        && asset.assetType() == selection.assetType())
                .findFirst().orElseThrow(() -> new ApiException(ApiCode.BAD_REQUEST,
                        "선택한 자산에 접근할 수 없습니다. Meta 자산 목록을 다시 조회해 주세요."))).toList();
        return service.saveMetaAssets(workspaceId, connectionId, userId, connection.getAccessToken(), selected);
    }
}
