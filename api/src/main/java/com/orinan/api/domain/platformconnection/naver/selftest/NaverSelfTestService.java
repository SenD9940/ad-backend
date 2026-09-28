package com.orinan.api.domain.platformconnection.naver.selftest;

import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.exception.ApiException;
import com.orinan.api.common.time.SeoulDateTimes;
import com.orinan.api.domain.platformconnection.controller.model.PlatformConnectionResponse;
import com.orinan.api.domain.platformconnection.naver.NaverCommerceClient.IssuedToken;
import com.orinan.api.domain.platformconnection.naver.NaverCommerceClient.SellerAccount;
import com.orinan.api.domain.platformconnection.service.PlatformConnectionService;
import com.orinan.api.domain.user.service.UserService;
import com.orinan.db.naverconnection.NaverConnectionEntity;
import com.orinan.db.naverconnection.NaverConnectionRepository;
import com.orinan.db.naverconnection.enums.NaverCredentialSource;
import com.orinan.db.naverconnection.enums.NaverTokenType;
import com.orinan.db.platformconnection.PlatformConnectionEntity;
import com.orinan.db.platformconnection.PlatformConnectionRepository;
import com.orinan.db.platformconnection.enums.ProviderType;
import com.orinan.db.user.enums.UserStatus;
import com.orinan.db.workspace.WorkspaceRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NaverSelfTestService {
    private final NaverSelfTestPolicy policy;
    private final WorkspaceRepository workspaces;
    private final PlatformConnectionRepository connections;
    private final NaverConnectionRepository details;
    private final PlatformConnectionService platformConnections;
    private final UserService users;
    private final EntityManager entityManager;

    @Transactional
    public PlatformConnectionResponse save(Long workspaceId, Long userId, NaverSelfTestPolicy.Credentials credentials,
                                           IssuedToken token, SellerAccount account) {
        var workspace = workspaces.findByIdForUpdate(workspaceId)
                .orElseThrow(() -> new ApiException(ApiCode.BAD_REQUEST, "존재하지 않는 워크스페이스입니다."));
        entityManager.refresh(workspace);
        policy.requireUnchanged(workspace, userId, credentials);
        users.findByIdAndStatusWithThrow(userId, UserStatus.REGISTERED);
        if (token == null || token.accessToken() == null || token.accessToken().isBlank()
                || token.expiresAt() == null || !token.expiresAt().isAfter(SeoulDateTimes.now())
                || account == null || account.accountUid() == null || account.accountUid().isBlank()
                || account.accountId() == null || account.accountId().isBlank()) {
            throw new ApiException(ApiCode.SERVER_ERROR, "네이버 인증 결과를 확인할 수 없습니다. 다시 시도해 주세요.");
        }
        var connection = connections.findByWorkspaceIdAndProviderTypeAndExternalAccountId(
                        workspaceId, ProviderType.NAVER, account.accountUid()).orElse(null);
        NaverConnectionEntity detail = null;
        if (connection != null) {
            entityManager.refresh(connection);
            detail = details.findById(connection.getId()).orElse(null);
            if (detail != null) {
                entityManager.refresh(detail);
                if (detail.getCredentialSource() == NaverCredentialSource.SOLUTION) {
                    throw new ApiException(ApiCode.BAD_REQUEST, "이미 간편 연결된 스토어입니다. 기존 연결에서 다시 인증해 주세요.");
                }
            }
        } else {
            connection = PlatformConnectionEntity.builder().workspace(workspace).providerType(ProviderType.NAVER)
                    .externalAccountId(account.accountUid()).build();
        }
        connection.setAccountName(account.accountId());
        connection.setRequiresReauth(false);
        connection = connections.saveAndFlush(connection);
        if (detail == null) detail = NaverConnectionEntity.builder().connection(connection).build();
        detail.setCredentialSource(NaverCredentialSource.MANUAL);
        detail.setClientId(credentials.appId());
        detail.setClientSecret(credentials.appSecret());
        detail.setTokenType(NaverTokenType.SELF);
        detail.setAccountId(null);
        detail.setApplicationRef(null);
        detail.setSolutionSubscriptionId(null);
        detail.setBoundSubscriptionGeneration(null);
        detail.setAccessToken(token.accessToken());
        detail.setExpiresAt(token.expiresAt());
        detail.setCredentialVersion(detail.getCredentialVersion() + 1);
        details.saveAndFlush(detail);
        return platformConnections.findById(workspaceId, connection.getId(), userId);
    }
}
