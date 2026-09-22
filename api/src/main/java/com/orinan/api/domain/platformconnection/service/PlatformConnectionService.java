package com.orinan.api.domain.platformconnection.service;

import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.exception.ApiException;
import com.orinan.api.common.time.SeoulDateTimes;
import com.orinan.api.domain.platformconnection.controller.model.PlatformAssetResponse;
import com.orinan.api.domain.platformconnection.controller.model.PlatformConnectionResponse;
import com.orinan.api.domain.platformconnection.meta.MetaGraphClient.AuthorizedAccount;
import com.orinan.api.domain.platformconnection.meta.MetaGraphClient.DiscoveredAsset;
import com.orinan.api.domain.user.exception.UserErrorCode;
import com.orinan.api.domain.user.service.UserService;
import com.orinan.api.domain.workspace.service.WorkspaceService;
import com.orinan.api.domain.workspacemember.service.WorkspaceMemberService;
import com.orinan.db.metaasset.MetaAssetEntity;
import com.orinan.db.metaasset.MetaAssetRepository;
import com.orinan.db.metaconnection.MetaConnectionEntity;
import com.orinan.db.metaconnection.MetaConnectionRepository;
import com.orinan.db.naverconnection.NaverConnectionRepository;
import com.orinan.db.platformasset.PlatformAssetEntity;
import com.orinan.db.platformasset.PlatformAssetRepository;
import com.orinan.db.platformconnection.PlatformConnectionEntity;
import com.orinan.db.platformconnection.PlatformConnectionRepository;
import com.orinan.db.platformconnection.enums.ProviderType;
import com.orinan.db.user.enums.UserStatus;
import com.orinan.db.workspace.WorkspaceEntity;
import com.orinan.db.workspace.WorkspaceRepository;
import com.orinan.db.workspacemember.WorkspaceMemberId;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class PlatformConnectionService {

    private final PlatformConnectionRepository connections;
    private final MetaConnectionRepository metaConnections;
    private final PlatformAssetRepository assets;
    private final MetaAssetRepository metaAssets;
    private final WorkspaceService workspaceService;
    private final WorkspaceRepository workspaces;
    private final WorkspaceMemberService members;
    private final UserService users;
    private final EntityManager entityManager;
    private final NaverConnectionRepository naverConnections;

    @Transactional(readOnly = true)
    public void requireOwner(Long workspaceId, Long userId) {
        validateOwner(workspaceService.findByIdWithThrow(workspaceId), userId);
    }

    @Transactional(readOnly = true)
    public void requireMember(Long workspaceId, Long userId) {
        validateMember(workspaceService.findByIdWithThrow(workspaceId), userId);
    }

    @Transactional(readOnly = true)
    public List<PlatformConnectionResponse> findAll(Long workspaceId, Long userId) {
        requireMember(workspaceId, userId);
        return connections.findAllByWorkspaceIdOrderByIdDesc(workspaceId).stream()
                .map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public PlatformConnectionResponse findById(Long workspaceId, Long connectionId, Long userId) {
        requireMember(workspaceId, userId);
        return connections.findByIdAndWorkspaceId(connectionId, workspaceId).map(this::toResponse)
                .orElseThrow(() -> new ApiException(ApiCode.BAD_REQUEST, "존재하지 않는 플랫폼 연결입니다."));
    }

    @Transactional
    public PlatformConnectionResponse saveMetaConnection(Long workspaceId, Long userId, AuthorizedAccount account) {
        var workspace = lockWorkspace(workspaceId);
        validateOwner(workspace, userId);
        users.findByIdAndStatusWithThrow(userId, UserStatus.REGISTERED);
        var connection = connections.findByWorkspaceIdAndProviderTypeAndExternalAccountId(
                        workspaceId, ProviderType.META, account.userId())
                .orElseGet(() -> PlatformConnectionEntity.builder()
                        .workspace(workspace).providerType(ProviderType.META)
                        .externalAccountId(account.userId()).build());
        connection.setAccountName(account.name());
        connection.setRequiresReauth(false);
        connection = connections.saveAndFlush(connection);

        var meta = metaConnections.findById(connection.getId()).orElse(null);
        if (meta == null) {
            meta = MetaConnectionEntity.builder().connection(connection).build();
        }
        meta.setAccessToken(account.accessToken());
        meta.setExpiresAt(account.expiresAt());
        meta.setGrantedScopes(account.grantedScopes());
        metaConnections.saveAndFlush(meta);
        return toResponse(connection);
    }

    @Transactional(readOnly = true)
    public MetaConnectionEntity getMetaConnection(Long workspaceId, Long connectionId, Long userId) {
        requireMember(workspaceId, userId);
        var connection = connections.findByIdAndWorkspaceId(connectionId, workspaceId)
                .orElseThrow(() -> new ApiException(ApiCode.BAD_REQUEST, "존재하지 않는 플랫폼 연결입니다."));
        // OSIV may retain entities loaded before another request reauthorized this connection.
        entityManager.refresh(connection);
        if (connection.getProviderType() != ProviderType.META) {
            throw new ApiException(ApiCode.BAD_REQUEST, "Meta 연결이 아닙니다.");
        }
        var meta = metaConnections.findById(connectionId)
                .orElseThrow(this::reauthRequired);
        entityManager.refresh(meta);
        if (Boolean.TRUE.equals(connection.getRequiresReauth()) || expired(meta)) {
            throw reauthRequired();
        }
        return meta;
    }

    @Transactional
    public List<PlatformAssetResponse> saveMetaAssets(Long workspaceId, Long connectionId, Long userId,
                                                     String verifiedToken, List<DiscoveredAsset> selected) {
        var workspace = lockWorkspace(workspaceId);
        validateMember(workspace, userId);
        users.findByIdAndStatusWithThrow(userId, UserStatus.REGISTERED);
        var meta = getMetaConnection(workspaceId, connectionId, userId);
        // If reauthorization happened during discovery, retry with the new account permissions.
        if (!meta.getAccessToken().equals(verifiedToken)) {
            throw new ApiException(ApiCode.BAD_REQUEST, "연결 정보가 변경되었습니다. 자산을 다시 조회해 주세요.");
        }
        for (var item : selected) {
            var asset = assets.findByConnectionIdAndPlatformTypeAndAssetTypeAndExternalId(
                            connectionId, item.platformType(), item.assetType(), item.externalId())
                    .orElseGet(() -> PlatformAssetEntity.builder()
                            .workspaceId(workspaceId).connectionId(connectionId)
                            .platformType(item.platformType()).assetType(item.assetType())
                            .externalId(item.externalId()).build());
            asset.setName(item.name());
            asset = assets.saveAndFlush(asset);
            var detail = metaAssets.findById(asset.getId()).orElse(null);
            if (detail == null) {
                detail = MetaAssetEntity.builder().asset(asset).build();
            }
            detail.setFacebookPageId(item.facebookPageId());
            metaAssets.saveAndFlush(detail);
        }
        return savedAssets(connectionId);
    }

    private WorkspaceEntity lockWorkspace(Long workspaceId) {
        var workspace = workspaces.findByIdForUpdate(workspaceId)
                .orElseThrow(() -> new ApiException(ApiCode.BAD_REQUEST, "존재하지 않는 워크스페이스입니다."));
        entityManager.refresh(workspace);
        return workspace;
    }

    private void validateMember(WorkspaceEntity workspace, Long userId) {
        if (!workspace.getUser().getId().equals(userId)
                && !members.exists(new WorkspaceMemberId(workspace.getId(), userId))) {
            throw new ApiException(UserErrorCode.USER_PERMISSION_DENY);
        }
    }

    private void validateOwner(WorkspaceEntity workspace, Long userId) {
        if (!workspace.getUser().getId().equals(userId)) {
            throw new ApiException(UserErrorCode.USER_PERMISSION_DENY);
        }
    }

    private PlatformConnectionResponse toResponse(PlatformConnectionEntity connection) {
        var meta = connection.getProviderType() == ProviderType.META
                ? metaConnections.findById(connection.getId()).orElse(null) : null;
        var naver = connection.getProviderType() == ProviderType.NAVER
                ? naverConnections.findById(connection.getId()).orElse(null) : null;
        boolean requiresReauth = Boolean.TRUE.equals(connection.getRequiresReauth())
                || (connection.getProviderType() == ProviderType.META && (meta == null || expired(meta)))
                || (connection.getProviderType() == ProviderType.NAVER && naver == null);
        // Naver client credentials can renew an expired token without another user login.
        var expiresAt = meta != null ? meta.getExpiresAt() : naver != null ? naver.getExpiresAt() : null;
        return new PlatformConnectionResponse(connection.getId(), connection.getWorkspace().getId(),
                connection.getProviderType(), connection.getExternalAccountId(), connection.getAccountName(),
                requiresReauth, expiresAt, savedAssets(connection.getId()));
    }

    private List<PlatformAssetResponse> savedAssets(Long connectionId) {
        return assets.findAllByConnectionIdOrderByIdAsc(connectionId).stream().map(asset -> {
            var detail = metaAssets.findById(asset.getId()).orElse(null);
            return new PlatformAssetResponse(asset.getId(), asset.getExternalId(), asset.getName(),
                    asset.getPlatformType(), asset.getAssetType(), detail == null ? null : detail.getFacebookPageId());
        }).toList();
    }

    private boolean expired(MetaConnectionEntity meta) {
        return meta.getExpiresAt() == null || !meta.getExpiresAt().isAfter(SeoulDateTimes.now());
    }

    private ApiException reauthRequired() {
        return new ApiException(ApiCode.BAD_REQUEST, "Meta 계정을 다시 연결해 주세요.");
    }
}
