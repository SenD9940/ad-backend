package com.orinan.api.domain.metaad.service;

import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.exception.ApiException;
import com.orinan.api.common.time.SeoulDateTimes;
import com.orinan.api.domain.platformconnection.service.PlatformConnectionService;
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

    public SavedAdAccount getAdAccount(Long workspaceId, Long assetId, Long userId) {
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
        return snapshot(asset, meta);
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
            if (!Objects.equals(previous.connectionId(), current.connectionId())
                    || !Objects.equals(previous.externalId(), current.externalId())
                    || !Objects.equals(previous.accessToken(), current.accessToken())) {
                throw new ApiException(ApiCode.BAD_REQUEST,
                        "연결 정보가 변경되었습니다. 광고 성과를 다시 조회해 주세요.");
            }
        }
    }

    private boolean isAdAccount(PlatformAssetEntity asset, Long workspaceId, Long connectionId) {
        return connectionId != null && Objects.equals(asset.getWorkspaceId(), workspaceId)
                && Objects.equals(asset.getConnectionId(), connectionId)
                && asset.getPlatformType() == PlatformType.FACEBOOK
                && asset.getAssetType() == AssetType.AD_ACCOUNT;
    }

    private void validateExternalId(PlatformAssetEntity asset) {
        if (asset.getExternalId() == null || !asset.getExternalId().matches("act_[0-9]+")) {
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

    public record SavedAdAccount(Long assetId, Long connectionId, String externalId, String name,
                                 String accessToken) {
        @Override
        public String toString() {
            return "SavedAdAccount[assetId=" + assetId + ", connectionId=" + connectionId
                    + ", externalId=" + externalId + ", accessToken=REDACTED]";
        }
    }
}
