package com.orinan.api.domain.navercommerce.service;

import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.navercommerce.controller.model.NaverStoreResponse.Store;
import com.orinan.api.domain.platformconnection.service.PlatformConnectionService;
import com.orinan.db.naverasset.NaverAssetRepository;
import com.orinan.db.platformasset.PlatformAssetRepository;
import com.orinan.db.platformasset.enums.AssetType;
import com.orinan.db.platformasset.enums.PlatformType;
import com.orinan.db.platformconnection.PlatformConnectionRepository;
import com.orinan.db.platformconnection.enums.ProviderType;
import com.orinan.db.workspace.WorkspaceRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NaverStoreAccessService {
    private final PlatformConnectionService permissions;
    private final PlatformAssetRepository assets;
    private final NaverAssetRepository naverAssets;
    private final PlatformConnectionRepository connections;
    private final WorkspaceRepository workspaces;
    private final EntityManager entityManager;

    public List<Store> list(Long workspaceId, Long userId) {
        member(workspaceId, userId);
        var result = new ArrayList<Store>();
        for (var connection : permissions.findAll(workspaceId, userId)) {
            if (connection.providerType() != ProviderType.NAVER) continue;
            for (var asset : connection.assets()) {
                if (asset.platformType() != PlatformType.NAVER_SMART_STORE || asset.assetType() != AssetType.STORE) continue;
                var store = get(workspaceId, asset.id(), userId);
                result.add(new Store(store.assetId(), store.connectionId(), store.channelNo(), store.name(),
                        store.storeUrl(), store.connectionName(), connection.requiresReauth() || store.requiresReauth()));
            }
        }
        return List.copyOf(result);
    }

    public Store get(Long workspaceId, Long assetId, Long userId) {
        member(workspaceId, userId);
        var asset = assets.findByIdAndWorkspaceId(assetId, workspaceId).orElseThrow(this::invalid);
        entityManager.refresh(asset);
        if (!Objects.equals(asset.getWorkspaceId(), workspaceId) || asset.getConnectionId() == null
                || asset.getPlatformType() != PlatformType.NAVER_SMART_STORE || asset.getAssetType() != AssetType.STORE
                || !numericChannel(asset.getExternalId())) throw invalid();
        var connection = connections.findByIdAndWorkspaceId(asset.getConnectionId(), workspaceId).orElseThrow(this::invalid);
        entityManager.refresh(connection);
        if (connection.getProviderType() != ProviderType.NAVER
                || !Objects.equals(connection.getWorkspace().getId(), workspaceId)) throw invalid();
        var detail = naverAssets.findById(assetId).orElseThrow(this::invalid);
        entityManager.refresh(detail);
        if (!"STOREFARM".equals(detail.getChannelType())) throw invalid();
        return new Store(assetId, connection.getId(), asset.getExternalId(),
                asset.getName() == null ? "스마트스토어 " + asset.getExternalId() : asset.getName(),
                detail.getChannelUrl(), connection.getAccountName(), Boolean.TRUE.equals(connection.getRequiresReauth()));
    }

    public void requireUnchanged(Long workspaceId, Long userId, Store expected) {
        var current = get(workspaceId, expected.assetId(), userId);
        if (!Objects.equals(expected.connectionId(), current.connectionId())
                || !Objects.equals(expected.channelNo(), current.channelNo()) || current.requiresReauth()) {
            throw new ApiException(ApiCode.BAD_REQUEST, "저장된 스토어 연결이 변경되었습니다. 자산을 다시 선택해 주세요.");
        }
    }

    private void member(Long workspaceId, Long userId) {
        var workspace = workspaces.findById(workspaceId).orElseThrow(this::invalid);
        entityManager.refresh(workspace);
        permissions.requireMember(workspaceId, userId);
    }

    private boolean numericChannel(String value) {
        try { return value != null && value.matches("[1-9][0-9]{0,18}") && Long.parseLong(value) > 0; }
        catch (NumberFormatException exception) { return false; }
    }

    private ApiException invalid() {
        return new ApiException(ApiCode.BAD_REQUEST, "워크스페이스에 저장된 스마트스토어 채널을 선택해 주세요.");
    }
}
