package com.orinan.api.domain.metaad.service;

import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.exception.ApiException;
import com.orinan.api.common.time.SeoulDateTimes;
import com.orinan.api.domain.platformconnection.service.PlatformConnectionService;
import com.orinan.db.metaasset.MetaAssetRepository;
import com.orinan.db.metaconnection.MetaConnectionEntity;
import com.orinan.db.metaconnection.MetaConnectionRepository;
import com.orinan.db.platformasset.PlatformAssetEntity;
import com.orinan.db.platformasset.PlatformAssetRepository;
import com.orinan.db.platformasset.enums.AssetType;
import com.orinan.db.platformasset.enums.PlatformType;
import com.orinan.db.platformconnection.PlatformConnectionEntity;
import com.orinan.db.platformconnection.PlatformConnectionRepository;
import com.orinan.db.platformconnection.enums.ProviderType;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MetaAdService {

    private final PlatformConnectionService platformConnections;
    private final PlatformConnectionRepository connections;
    private final MetaConnectionRepository metaConnections;
    private final PlatformAssetRepository assets;
    private final EntityManager entityManager;
    private final MetaAssetRepository metaAssets;

    public SavedAdAccount getAdAccount(Long workspaceId, Long assetId, Long userId) {
        return loadAdAccount(workspaceId, assetId, userId, false);
    }

    public SavedAdAccount getAdAccountForManagement(Long workspaceId, Long assetId, Long userId) {
        return loadAdAccount(workspaceId, assetId, userId, true);
    }

    public void verifyManagementUnchanged(Long workspaceId, Long userId, SavedAdAccount expected) {
        var current = getAdAccountForManagement(workspaceId, expected.assetId(), userId);
        if (!sameAccount(expected, current)) {
            throw new ApiException(ApiCode.BAD_REQUEST, "연결 정보가 변경되었습니다. 광고 계정을 다시 조회해 주세요.");
        }
    }

    private SavedAdAccount loadAdAccount(Long workspaceId, Long assetId, Long userId, boolean management) {
        platformConnections.requireMember(workspaceId, userId);
        var asset = assets.findByIdAndWorkspaceId(assetId, workspaceId)
                .orElseThrow(this::invalidAccount);
        entityManager.refresh(asset);
        if (!isAdAccount(asset, workspaceId, asset.getConnectionId())) {
            throw invalidAccount();
        }
        validateExternalId(asset);
        var meta = platformConnections.getMetaConnection(workspaceId, asset.getConnectionId(), userId);
        if (!usable(meta)) {
            throw reauthRequired();
        }
        if (management && (meta.getGrantedScopes() == null || Arrays.stream(meta.getGrantedScopes().split(","))
                .map(String::trim).noneMatch("ads_management"::equals))) {
            throw new ApiException(ApiCode.BAD_REQUEST, "광고 관리 권한이 필요합니다. Meta 계정을 다시 연결해 주세요.");
        }
        return snapshot(asset, meta);
    }

    public AdIdentity getAdIdentity(Long workspaceId, Long userId, SavedAdAccount account,
                                    Long pageAssetId, Long instagramAssetId) {
        var current = getAdAccountForManagement(workspaceId, account.assetId(), userId);
        if (!sameAccount(account, current)) {
            throw new ApiException(ApiCode.BAD_REQUEST, "연결 정보가 변경되었습니다. 광고 계정을 다시 조회해 주세요.");
        }
        var page = loadIdentityAsset(workspaceId, current.connectionId(), pageAssetId,
                PlatformType.FACEBOOK, AssetType.PAGE);
        if (instagramAssetId == null) {
            return new AdIdentity(page.getExternalId(), null);
        }
        var instagram = loadIdentityAsset(workspaceId, current.connectionId(), instagramAssetId,
                PlatformType.INSTAGRAM, AssetType.PROFILE);
        var detail = metaAssets.findById(instagramAssetId).orElseThrow(this::invalidIdentity);
        entityManager.refresh(detail);
        if (!Objects.equals(detail.getFacebookPageId(), page.getExternalId())) {
            throw invalidIdentity();
        }
        return new AdIdentity(page.getExternalId(), instagram.getExternalId());
    }

    private PlatformAssetEntity loadIdentityAsset(Long workspaceId, Long connectionId, Long assetId,
                                                   PlatformType platformType, AssetType assetType) {
        if (assetId == null) {
            throw invalidIdentity();
        }
        var asset = assets.findByIdAndWorkspaceId(assetId, workspaceId).orElseThrow(this::invalidIdentity);
        entityManager.refresh(asset);
        if (!Objects.equals(asset.getWorkspaceId(), workspaceId)
                || !Objects.equals(asset.getConnectionId(), connectionId)
                || asset.getPlatformType() != platformType || asset.getAssetType() != assetType
                || asset.getExternalId() == null || !asset.getExternalId().matches("[0-9]{1,32}")) {
            throw invalidIdentity();
        }
        return asset;
    }

    public List<SavedAdAccount> getAdAccounts(Long workspaceId, Long userId) {
        platformConnections.requireMember(workspaceId, userId);
        var workspaceConnections = connections.findAllByWorkspaceIdOrderByIdDesc(workspaceId).stream()
                .peek(entityManager::refresh)
                .filter(connection -> connection.getProviderType() == ProviderType.META
                        && Objects.equals(connection.getWorkspace().getId(), workspaceId))
                .sorted(Comparator.comparing(PlatformConnectionEntity::getUpdatedAt,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(PlatformConnectionEntity::getId, Comparator.reverseOrder()))
                .toList();

        var accountIds = new HashSet<String>();
        var selected = new LinkedHashMap<String, SavedAdAccount>();
        for (var connection : workspaceConnections) {
            var savedAssets = assets.findAllByConnectionIdOrderByIdAsc(connection.getId()).stream()
                    .peek(entityManager::refresh)
                    .filter(asset -> isAdAccount(asset, workspaceId, connection.getId()))
                    .toList();
            for (var asset : savedAssets) {
                validateExternalId(asset);
                accountIds.add(asset.getExternalId());
            }
            if (savedAssets.isEmpty() || Boolean.TRUE.equals(connection.getRequiresReauth())) {
                continue;
            }
            var meta = metaConnections.findById(connection.getId()).orElse(null);
            if (meta != null) {
                entityManager.refresh(meta);
            }
            // Inspect unusable duplicates before invoking the transactional authorization service;
            // catching its exceptions here would leave this transaction marked rollback-only.
            if (!usable(meta)) {
                continue;
            }
            meta = platformConnections.getMetaConnection(workspaceId, connection.getId(), userId);
            if (!usable(meta)) {
                throw reauthRequired();
            }
            for (var asset : savedAssets) {
                selected.putIfAbsent(asset.getExternalId(), snapshot(asset, meta));
            }
        }
        if (selected.size() != accountIds.size()) {
            throw reauthRequired();
        }
        return List.copyOf(selected.values());
    }

    public void verifyUnchanged(Long workspaceId, Long userId, List<SavedAdAccount> expected) {
        platformConnections.requireMember(workspaceId, userId);
        for (var previous : expected) {
            var current = getAdAccount(workspaceId, previous.assetId(), userId);
            if (!sameAccount(previous, current)) {
                throw new ApiException(ApiCode.BAD_REQUEST,
                        "연결 정보가 변경되었습니다. 광고 성과를 다시 조회해 주세요.");
            }
        }
    }

    private boolean sameAccount(SavedAdAccount previous, SavedAdAccount current) {
        return Objects.equals(previous.connectionId(), current.connectionId())
                && Objects.equals(previous.externalId(), current.externalId())
                && Objects.equals(previous.accessToken(), current.accessToken());
    }

    private boolean isAdAccount(PlatformAssetEntity asset, Long workspaceId, Long connectionId) {
        return connectionId != null && Objects.equals(asset.getWorkspaceId(), workspaceId)
                && Objects.equals(asset.getConnectionId(), connectionId)
                && asset.getPlatformType() == PlatformType.FACEBOOK
                && asset.getAssetType() == AssetType.AD_ACCOUNT;
    }

    private void validateExternalId(PlatformAssetEntity asset) {
        if (asset.getExternalId() == null || !asset.getExternalId().matches("act_[0-9]{1,32}")) {
            throw invalidAccount();
        }
    }

    private boolean usable(MetaConnectionEntity meta) {
        return meta != null && meta.getAccessToken() != null && !meta.getAccessToken().isBlank()
                && meta.getExpiresAt() != null && meta.getExpiresAt().isAfter(SeoulDateTimes.now());
    }

    private SavedAdAccount snapshot(PlatformAssetEntity asset, MetaConnectionEntity meta) {
        return new SavedAdAccount(asset.getId(), asset.getConnectionId(), asset.getExternalId(),
                asset.getName(), meta.getAccessToken());
    }

    private ApiException invalidAccount() {
        return new ApiException(ApiCode.BAD_REQUEST, "저장된 Meta 광고 계정이 아닙니다.");
    }

    private ApiException reauthRequired() {
        return new ApiException(ApiCode.BAD_REQUEST, "Meta 계정을 다시 연결해 주세요.");
    }

    private ApiException invalidIdentity() {
        return new ApiException(ApiCode.BAD_REQUEST, "같은 Meta 연결에 저장된 페이지와 연결된 Instagram 프로필을 선택해 주세요.");
    }

    public record AdIdentity(String pageId, String instagramUserId) {
    }

    public record SavedAdAccount(Long assetId, Long connectionId, String externalId, String name,
                                 String accessToken) {
        @Override
        public String toString() {
            return "SavedAdAccount[assetId=" + assetId + ", connectionId=" + connectionId
                    + ", externalId=" + externalId + ", accessToken=REDACTED]";
        }
    }
}
