package com.orinan.api.domain.platformconnection.service;

import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.exception.ApiException;
import com.orinan.api.common.time.SeoulDateTimes;
import com.orinan.api.domain.platformconnection.controller.model.NaverChannelResponse;
import com.orinan.api.domain.platformconnection.controller.model.NaverConnectRequest;
import com.orinan.api.domain.platformconnection.controller.model.PlatformConnectionResponse;
import com.orinan.api.domain.platformconnection.naver.NaverCommerceClient.Channel;
import com.orinan.api.domain.platformconnection.naver.NaverCommerceClient.IssuedToken;
import com.orinan.api.domain.platformconnection.naver.NaverCommerceClient.SellerAccount;
import com.orinan.api.domain.platformconnection.naver.integration.NaverSolutionCredentialResolver;
import com.orinan.api.domain.user.service.UserService;
import com.orinan.db.naverasset.NaverAssetEntity;
import com.orinan.db.naverasset.NaverAssetRepository;
import com.orinan.db.naverconnection.NaverConnectionEntity;
import com.orinan.db.naverconnection.NaverConnectionRepository;
import com.orinan.db.naverconnection.enums.NaverTokenType;
import com.orinan.db.naverconnection.enums.NaverCredentialSource;
import com.orinan.db.platformasset.PlatformAssetEntity;
import com.orinan.db.platformasset.PlatformAssetRepository;
import com.orinan.db.platformasset.enums.AssetType;
import com.orinan.db.platformasset.enums.PlatformType;
import com.orinan.db.platformconnection.PlatformConnectionEntity;
import com.orinan.db.platformconnection.PlatformConnectionRepository;
import com.orinan.db.platformconnection.enums.ProviderType;
import com.orinan.db.user.enums.UserStatus;
import com.orinan.db.workspace.WorkspaceEntity;
import com.orinan.db.workspace.WorkspaceRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class NaverConnectionService {

    private final PlatformConnectionRepository connections;
    private final NaverConnectionRepository naverConnections;
    private final PlatformAssetRepository assets;
    private final NaverAssetRepository naverAssets;
    private final WorkspaceRepository workspaces;
    private final PlatformConnectionService platformConnections;
    private final UserService users;
    private final EntityManager entityManager;
    private NaverSolutionCredentialResolver solutionCredentials;

    @Autowired(required = false)
    public void setSolutionCredentials(NaverSolutionCredentialResolver solutionCredentials) {
        this.solutionCredentials = solutionCredentials;
    }

    @Transactional
    public PlatformConnectionResponse saveConnection(Long workspaceId, Long userId, NaverConnectRequest request,
                                                       IssuedToken token, SellerAccount account) {
        var workspace = lockWorkspace(workspaceId);
        platformConnections.requireOwner(workspaceId, userId);
        users.findByIdAndStatusWithThrow(userId, UserStatus.REGISTERED);
        var connection = connections.findByWorkspaceIdAndProviderTypeAndExternalAccountId(
                        workspaceId, ProviderType.NAVER, account.accountUid())
                .orElseGet(() -> PlatformConnectionEntity.builder().workspace(workspace)
                        .providerType(ProviderType.NAVER).externalAccountId(account.accountUid()).build());
        connection.setAccountName(account.accountId());
        connection.setRequiresReauth(false);
        connection = connections.saveAndFlush(connection);

        var detail = naverConnections.findById(connection.getId()).orElse(null);
        if (detail == null) {
            detail = NaverConnectionEntity.builder().connection(connection).build();
        }
        detail.setClientId(request.getClientId());
        detail.setClientSecret(request.getClientSecret());
        detail.setTokenType(request.getTokenType());
        detail.setAccountId(request.getAccountId());
        detail.setAccessToken(token.accessToken());
        detail.setExpiresAt(token.expiresAt());
        detail.setCredentialSource(NaverCredentialSource.MANUAL);
        detail.setApplicationRef(null);
        detail.setSolutionSubscriptionId(null);
        detail.setBoundSubscriptionGeneration(null);
        detail.setCredentialVersion(detail.getCredentialVersion() + 1);
        naverConnections.saveAndFlush(detail);
        return platformConnections.findById(workspaceId, connection.getId(), userId);
    }

    @Transactional(readOnly = true)
    public Credentials getCredentials(Long workspaceId, Long connectionId, Long userId) {
        platformConnections.requireMember(workspaceId, userId);
        var detail = loadConnection(workspaceId, connectionId);
        return snapshot(detail);
    }

    @Transactional(readOnly = true)
    public void requireUnchanged(Long workspaceId, Long connectionId, Long userId, Credentials expected) {
        platformConnections.requireMember(workspaceId, userId);
        var current = snapshot(loadConnection(workspaceId, connectionId));
        if (!current.sameAccountCredentials(expected) || !Objects.equals(current.accessToken(), expected.accessToken())) {
            throw connectionChanged();
        }
    }

    @Transactional
    public Credentials updateToken(Long workspaceId, Long connectionId, Long userId,
                                   Credentials expected, IssuedToken token) {
        lockWorkspace(workspaceId);
        platformConnections.requireMember(workspaceId, userId);
        users.findByIdAndStatusWithThrow(userId, UserStatus.REGISTERED);
        var detail = loadConnection(workspaceId, connectionId);
        var current = snapshot(detail);
        if (!current.sameAccountCredentials(expected)) {
            throw connectionChanged();
        }
        // Concurrent renewals can reuse a token already saved by another request.
        if (!current.accessToken().equals(expected.accessToken())
                && current.expiresAt() != null && current.expiresAt().isAfter(SeoulDateTimes.now().plusMinutes(1))) {
            return current;
        }
        detail.setAccessToken(token.accessToken());
        detail.setExpiresAt(token.expiresAt());
        naverConnections.saveAndFlush(detail);
        return snapshot(detail);
    }

    @Transactional
    public void markRequiresReauth(Long workspaceId, Long connectionId, Long userId, Credentials expected) {
        lockWorkspace(workspaceId);
        platformConnections.requireMember(workspaceId, userId);
        var detail = loadConnection(workspaceId, connectionId);
        var current = snapshot(detail);
        // A failed old request must not invalidate credentials that the owner just replaced.
        if (current.sameAccountCredentials(expected) && current.accessToken().equals(expected.accessToken())) {
            detail.getConnection().setRequiresReauth(true);
            connections.saveAndFlush(detail.getConnection());
        }
    }

    @Transactional
    public List<NaverChannelResponse> saveChannels(Long workspaceId, Long connectionId, Long userId,
                                                  Credentials expected, List<Channel> selected) {
        lockWorkspace(workspaceId);
        platformConnections.requireMember(workspaceId, userId);
        users.findByIdAndStatusWithThrow(userId, UserStatus.REGISTERED);
        var current = snapshot(loadConnection(workspaceId, connectionId));
        if (!current.sameAccountCredentials(expected) || !current.accessToken().equals(expected.accessToken())) {
            throw connectionChanged();
        }
        for (var channel : selected) {
            var asset = assets.findByConnectionIdAndPlatformTypeAndAssetTypeAndExternalId(
                            connectionId, PlatformType.NAVER_SMART_STORE, AssetType.STORE, Long.toString(channel.channelNo()))
                    .orElseGet(() -> PlatformAssetEntity.builder().workspaceId(workspaceId).connectionId(connectionId)
                            .platformType(PlatformType.NAVER_SMART_STORE).assetType(AssetType.STORE)
                            .externalId(Long.toString(channel.channelNo())).build());
            asset.setName(channel.name());
            asset = assets.saveAndFlush(asset);
            var detail = naverAssets.findById(asset.getId()).orElse(null);
            if (detail == null) {
                detail = NaverAssetEntity.builder().asset(asset).build();
            }
            detail.setChannelType(channel.channelType());
            detail.setChannelUrl(channel.url());
            naverAssets.saveAndFlush(detail);
        }
        return assets.findAllByConnectionIdOrderByIdAsc(connectionId).stream()
                .filter(asset -> asset.getPlatformType() == PlatformType.NAVER_SMART_STORE && asset.getAssetType() == AssetType.STORE)
                .map(asset -> {
                    var detail = naverAssets.findById(asset.getId())
                            .orElseThrow(() -> new ApiException(ApiCode.SERVER_ERROR, "네이버 채널 정보를 확인해 주세요."));
                    return new NaverChannelResponse(asset.getId(), Long.parseLong(asset.getExternalId()),
                            detail.getChannelType(), asset.getName(), detail.getChannelUrl());
                }).toList();
    }

    private NaverConnectionEntity loadConnection(Long workspaceId, Long connectionId) {
        var connection = connections.findByIdAndWorkspaceId(connectionId, workspaceId)
                .orElseThrow(() -> new ApiException(ApiCode.BAD_REQUEST, "존재하지 않는 플랫폼 연결입니다."));
        entityManager.refresh(connection);
        if (connection.getProviderType() != ProviderType.NAVER) {
            throw new ApiException(ApiCode.BAD_REQUEST, "네이버 연결이 아닙니다.");
        }
        if (Boolean.TRUE.equals(connection.getRequiresReauth())) {
            throw reauthRequired();
        }
        var detail = naverConnections.findById(connectionId).orElseThrow(this::reauthRequired);
        entityManager.refresh(detail);
        return detail;
    }

    private WorkspaceEntity lockWorkspace(Long workspaceId) {
        var workspace = workspaces.findByIdForUpdate(workspaceId)
                .orElseThrow(() -> new ApiException(ApiCode.BAD_REQUEST, "존재하지 않는 워크스페이스입니다."));
        entityManager.refresh(workspace);
        return workspace;
    }

    private Credentials snapshot(NaverConnectionEntity detail) {
        String clientId = detail.getClientId();
        String clientSecret = detail.getClientSecret();
        long subscriptionVersion = 0;
        long applicationCredentialVersion = 0;
        var source = detail.getCredentialSource() == null ? NaverCredentialSource.MANUAL : detail.getCredentialSource();
        if (source == NaverCredentialSource.SOLUTION) {
            if (solutionCredentials == null) throw reauthRequired();
            var resolved = solutionCredentials.resolve(detail);
            clientId = resolved.clientId();
            clientSecret = resolved.clientSecret();
            subscriptionVersion = resolved.subscriptionVersion();
            applicationCredentialVersion = resolved.applicationCredentialVersion();
        }
        if (clientId == null || clientSecret == null || detail.getAccessToken() == null) throw reauthRequired();
        return new Credentials(detail.getConnection().getExternalAccountId(), clientId, clientSecret,
                detail.getTokenType(), detail.getAccountId(), detail.getAccessToken(), detail.getExpiresAt(),
                source, detail.getApplicationRef(), detail.getSolutionSubscriptionId(),
                detail.getBoundSubscriptionGeneration(), detail.getCredentialVersion(), subscriptionVersion, applicationCredentialVersion);
    }

    private ApiException reauthRequired() {
        return new ApiException(ApiCode.BAD_REQUEST, "네이버 스마트스토어를 다시 연결해 주세요.");
    }

    private ApiException connectionChanged() {
        return new ApiException(ApiCode.BAD_REQUEST, "연결 정보가 변경되었습니다. 네이버 채널을 다시 조회해 주세요.");
    }

    public record Credentials(String accountUid, String clientId, String clientSecret, NaverTokenType tokenType,
                              String accountId, String accessToken, LocalDateTime expiresAt,
                              NaverCredentialSource credentialSource, String applicationRef, Long subscriptionId,
                              Long subscriptionGeneration, long credentialVersion, long subscriptionVersion,
                              long applicationCredentialVersion) {

        public Credentials(String accountUid, String clientId, String clientSecret, NaverTokenType tokenType,
                           String accountId, String accessToken, LocalDateTime expiresAt) {
            this(accountUid, clientId, clientSecret, tokenType, accountId, accessToken, expiresAt,
                    NaverCredentialSource.MANUAL, null, null, null, 0, 0, 0);
        }

        boolean sameAccountCredentials(Credentials other) {
            return Objects.equals(accountUid, other.accountUid) && Objects.equals(clientId, other.clientId)
                    && Objects.equals(clientSecret, other.clientSecret) && tokenType == other.tokenType
                    && Objects.equals(accountId, other.accountId)
                    && credentialSource == other.credentialSource
                    && Objects.equals(applicationRef, other.applicationRef)
                    && Objects.equals(subscriptionId, other.subscriptionId)
                    && Objects.equals(subscriptionGeneration, other.subscriptionGeneration)
                    && credentialVersion == other.credentialVersion && subscriptionVersion == other.subscriptionVersion
                    && applicationCredentialVersion == other.applicationCredentialVersion;
        }

        @Override
        public String toString() {
            return "Credentials[REDACTED]";
        }
    }
}
