package com.orinan.api.domain.platformconnection.naver.integration;

import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.exception.ApiException;
import com.orinan.api.common.time.SeoulDateTimes;
import com.orinan.api.domain.platformconnection.naver.NaverCommerceClient;
import com.orinan.api.domain.platformconnection.naver.authorization.NaverAuthorizationConnectionBridge;
import com.orinan.api.domain.platformconnection.service.PlatformConnectionService;
import com.orinan.api.domain.user.service.UserService;
import com.orinan.db.naverconnection.NaverConnectionEntity;
import com.orinan.db.naverconnection.NaverConnectionRepository;
import com.orinan.db.naverconnection.enums.NaverCredentialSource;
import com.orinan.db.naverconnection.enums.NaverTokenType;
import com.orinan.db.naversolution.NaverSolutionSubscriptionRepository;
import com.orinan.db.platformconnection.PlatformConnectionEntity;
import com.orinan.db.platformconnection.PlatformConnectionRepository;
import com.orinan.db.platformconnection.enums.ProviderType;
import com.orinan.db.user.enums.UserStatus;
import com.orinan.db.workspace.WorkspaceRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
@RequiredArgsConstructor
public class PersistentNaverAuthorizationConnectionBridge implements NaverAuthorizationConnectionBridge {
    private final PlatformConnectionService permissions;
    private final PlatformConnectionRepository connections;
    private final NaverConnectionRepository details;
    private final WorkspaceRepository workspaces;
    private final NaverSolutionSubscriptionRepository subscriptions;
    private final NaverSolutionCredentialResolver credentials;
    private final UserService users;
    private final EntityManager entityManager;

    @Override
    @Transactional(readOnly = true)
    public String reconnectAccountUid(Long workspaceId, Long userId, Long connectionId) {
        permissions.requireOwner(workspaceId, userId);
        var connection = connections.findByIdAndWorkspaceId(connectionId, workspaceId).orElseThrow(this::invalidTarget);
        entityManager.refresh(connection);
        if (connection.getProviderType() != ProviderType.NAVER || connection.getExternalAccountId() == null) throw invalidTarget();
        return connection.getExternalAccountId();
    }

    @Override
    @Transactional
    public Long save(Long workspaceId, Long userId, Long reconnectConnectionId, String applicationRef,
                     Long subscriptionId, long generation, NaverCommerceClient.IssuedToken token,
                     NaverCommerceClient.SellerAccount account) {
        var subscription = subscriptions.findByIdForUpdate(subscriptionId).orElseThrow(this::invalidTarget);
        entityManager.refresh(subscription);
        credentials.requireActive(subscription, applicationRef, account.accountUid(), generation);
        var workspace = workspaces.findByIdForUpdate(workspaceId).orElseThrow(this::invalidTarget);
        entityManager.refresh(workspace);
        permissions.requireOwner(workspaceId, userId);
        users.findByIdAndStatusWithThrow(userId, UserStatus.REGISTERED);
        if (token == null || token.accessToken() == null || token.accessToken().isBlank()
                || token.expiresAt() == null || !token.expiresAt().isAfter(SeoulDateTimes.now())) throw invalidTarget();

        PlatformConnectionEntity connection;
        if (reconnectConnectionId != null) {
            connection = connections.findByIdAndWorkspaceId(reconnectConnectionId, workspaceId).orElseThrow(this::invalidTarget);
            entityManager.refresh(connection);
            if (connection.getProviderType() != ProviderType.NAVER
                    || !Objects.equals(connection.getExternalAccountId(), account.accountUid())) throw invalidTarget();
        } else {
            connection = connections.findByWorkspaceIdAndProviderTypeAndExternalAccountId(workspaceId, ProviderType.NAVER,
                    account.accountUid()).orElse(null);
            if (connection != null) {
                var previous = details.findById(connection.getId()).orElse(null);
                if (previous != null && previous.getCredentialSource() != NaverCredentialSource.SOLUTION) {
                    throw new ApiException(ApiCode.BAD_REQUEST, "이미 키 입력으로 연결된 스토어입니다. 기존 연결의 인증 방식 전환을 사용해 주세요.");
                }
            } else {
                connection = PlatformConnectionEntity.builder().workspace(workspace).providerType(ProviderType.NAVER)
                        .externalAccountId(account.accountUid()).build();
            }
        }
        connection.setAccountName(account.accountId());
        connection.setRequiresReauth(false);
        connection = connections.saveAndFlush(connection);
        var detail = details.findById(connection.getId()).orElse(null);
        if (detail == null) detail = NaverConnectionEntity.builder().connection(connection).build();
        detail.setCredentialSource(NaverCredentialSource.SOLUTION);
        detail.setApplicationRef(applicationRef);
        detail.setSolutionSubscriptionId(subscriptionId);
        detail.setBoundSubscriptionGeneration(generation);
        detail.setClientId(null);
        detail.setClientSecret(null);
        detail.setTokenType(NaverTokenType.SELLER);
        detail.setAccountId(account.accountUid());
        detail.setAccessToken(token.accessToken());
        detail.setExpiresAt(token.expiresAt());
        detail.setCredentialVersion(detail.getCredentialVersion() + 1);
        details.saveAndFlush(detail);
        return connection.getId();
    }

    private ApiException invalidTarget() {
        return new ApiException(ApiCode.BAD_REQUEST, "연결할 워크스페이스, 판매자와 구독 정보를 다시 확인해 주세요.");
    }
}
