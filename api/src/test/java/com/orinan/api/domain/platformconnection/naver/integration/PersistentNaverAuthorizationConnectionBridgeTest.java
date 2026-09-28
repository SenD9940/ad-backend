package com.orinan.api.domain.platformconnection.naver.integration;

import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.exception.ApiException;
import com.orinan.api.common.time.SeoulDateTimes;
import com.orinan.api.domain.platformconnection.naver.NaverCommerceClient;
import com.orinan.api.domain.platformconnection.service.PlatformConnectionService;
import com.orinan.api.domain.user.service.UserService;
import com.orinan.db.naverconnection.NaverConnectionEntity;
import com.orinan.db.naverconnection.NaverConnectionRepository;
import com.orinan.db.naverconnection.enums.NaverCredentialSource;
import com.orinan.db.naverconnection.enums.NaverTokenType;
import com.orinan.db.naversolution.NaverSolutionSubscriptionEntity;
import com.orinan.db.naversolution.NaverSolutionSubscriptionRepository;
import com.orinan.db.platformconnection.PlatformConnectionEntity;
import com.orinan.db.platformconnection.PlatformConnectionRepository;
import com.orinan.db.platformconnection.enums.ProviderType;
import com.orinan.db.user.UserEntity;
import com.orinan.db.user.enums.UserStatus;
import com.orinan.db.workspace.WorkspaceEntity;
import com.orinan.db.workspace.WorkspaceRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PersistentNaverAuthorizationConnectionBridgeTest {
    private final PlatformConnectionService permissions = mock(PlatformConnectionService.class);
    private final PlatformConnectionRepository connections = mock(PlatformConnectionRepository.class);
    private final NaverConnectionRepository details = mock(NaverConnectionRepository.class);
    private final WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
    private final NaverSolutionSubscriptionRepository subscriptions = mock(NaverSolutionSubscriptionRepository.class);
    private final NaverSolutionCredentialResolver credentials = mock(NaverSolutionCredentialResolver.class);
    private final UserService users = mock(UserService.class);
    private final EntityManager manager = mock(EntityManager.class);
    private final PersistentNaverAuthorizationConnectionBridge bridge = new PersistentNaverAuthorizationConnectionBridge(
            permissions, connections, details, workspaces, subscriptions, credentials, users, manager);
    private final WorkspaceEntity workspace = WorkspaceEntity.builder().id(10L).name("워크스페이스")
            .user(UserEntity.builder().id(1L).build()).build();
    private final NaverSolutionSubscriptionEntity subscription = new NaverSolutionSubscriptionEntity();
    private PlatformConnectionEntity connection;
    private NaverConnectionEntity detail;

    @BeforeEach void setUp() {
        subscription.setId(7L); subscription.setAccountUid("seller"); subscription.setGeneration(2); subscription.setStatus("ACTIVE");
        when(subscriptions.findByIdForUpdate(7L)).thenReturn(Optional.of(subscription));
        when(workspaces.findByIdForUpdate(10L)).thenReturn(Optional.of(workspace));
        connection = PlatformConnectionEntity.builder().id(20L).workspace(workspace).providerType(ProviderType.NAVER)
                .externalAccountId("seller").accountName("old-name").requiresReauth(true).build();
        detail = NaverConnectionEntity.builder().connection(connection).connectionId(20L)
                .credentialSource(NaverCredentialSource.MANUAL).clientId("previous-app")
                .clientSecret("previous-secret").tokenType(NaverTokenType.SELF)
                .accessToken("previous-token").credentialVersion(5).build();
        when(connections.findByIdAndWorkspaceId(20L,10L)).thenReturn(Optional.of(connection));
        when(connections.saveAndFlush(any())).thenAnswer(invocation -> {
            PlatformConnectionEntity value = invocation.getArgument(0);
            if (value.getId() == null) value.setId(21L);
            return value;
        });
        when(details.findById(20L)).thenReturn(Optional.of(detail));
    }

    @Test void explicitManualConversionPreservesConnectionAndAssetForeignKeyIdentityWithoutCopyingAppSecrets() {
        assertThat(save(20L)).isEqualTo(20L);
        assertThat(detail.getConnectionId()).isEqualTo(20L);
        assertThat(detail.getConnection()).isSameAs(connection);
        assertThat(detail.getCredentialSource()).isEqualTo(NaverCredentialSource.SOLUTION);
        assertThat(detail.getClientId()).isNull(); assertThat(detail.getClientSecret()).isNull();
        assertThat(detail.getApplicationRef()).isEqualTo("application");
        assertThat(detail.getSolutionSubscriptionId()).isEqualTo(7L);
        assertThat(detail.getBoundSubscriptionGeneration()).isEqualTo(2L);
        assertThat(detail.getTokenType()).isEqualTo(NaverTokenType.SELLER);
        assertThat(detail.getAccountId()).isEqualTo("seller");
        assertThat(detail.getAccessToken()).isEqualTo("verified-token");
        assertThat(detail.getCredentialVersion()).isEqualTo(6);
        assertThat(connection.getRequiresReauth()).isFalse();
        verify(connections).saveAndFlush(same(connection)); verify(details).saveAndFlush(same(detail));
        verify(users).findByIdAndStatusWithThrow(1L, UserStatus.REGISTERED);
        var ordered = inOrder(subscriptions, manager, credentials, workspaces, permissions, connections);
        ordered.verify(subscriptions).findByIdForUpdate(7L); ordered.verify(manager).refresh(subscription);
        ordered.verify(credentials).requireActive(subscription,"application","seller",2L);
        ordered.verify(workspaces).findByIdForUpdate(10L); ordered.verify(manager).refresh(workspace);
        ordered.verify(permissions).requireOwner(10L,1L); ordered.verify(connections).findByIdAndWorkspaceId(20L,10L);
    }

    @Test void newAuthorizationCannotSilentlyOverwriteExistingManualCredentials() {
        when(connections.findByWorkspaceIdAndProviderTypeAndExternalAccountId(10L,ProviderType.NAVER,"seller"))
                .thenReturn(Optional.of(connection));
        assertThatThrownBy(() -> save(null)).isInstanceOf(ApiException.class);
        assertThat(detail.getCredentialSource()).isEqualTo(NaverCredentialSource.MANUAL);
        assertThat(detail.getClientSecret()).isEqualTo("previous-secret");
        assertThat(detail.getAccessToken()).isEqualTo("previous-token");
        verify(connections,never()).saveAndFlush(any()); verify(details,never()).saveAndFlush(any());
    }

    @Test void reconnectingDifferentSellerOrProviderPreservesExistingConnection() {
        connection.setExternalAccountId("other-seller");
        assertThatThrownBy(() -> save(20L)).isInstanceOf(ApiException.class);
        connection.setExternalAccountId("seller"); connection.setProviderType(ProviderType.META);
        assertThatThrownBy(() -> save(20L)).isInstanceOf(ApiException.class);
        assertThat(detail.getAccessToken()).isEqualTo("previous-token");
        verify(connections,never()).saveAndFlush(any()); verify(details,never()).saveAndFlush(any());
    }

    @Test void revokedOrSupersededGrantIsRejectedBeforeReadingAndWritingWorkspaceConnection() {
        doThrow(new ApiException(ApiCode.BAD_REQUEST)).when(credentials).requireActive(subscription,"application","seller",2L);
        assertThatThrownBy(() -> save(20L)).isInstanceOf(ApiException.class);
        verifyNoInteractions(workspaces,permissions,users);
        verify(connections,never()).saveAndFlush(any()); verify(details,never()).saveAndFlush(any());
    }

    @Test void lostWorkspaceOwnershipRejectsPersistenceAfterRefreshingWorkspaceUnderLock() {
        doThrow(new ApiException(ApiCode.BAD_REQUEST)).when(permissions).requireOwner(10L,1L);
        assertThatThrownBy(() -> save(20L)).isInstanceOf(ApiException.class);
        verify(manager).refresh(workspace);
        verify(connections,never()).findByIdAndWorkspaceId(anyLong(),anyLong());
        verify(connections,never()).saveAndFlush(any()); verify(details,never()).saveAndFlush(any());
    }

    @Test void newConnectionStoresOnlySellerCredentialAndVerifiedSubscriptionBinding() {
        assertThat(save(null)).isEqualTo(21L);
        var captured = ArgumentCaptor.forClass(NaverConnectionEntity.class);
        verify(details).saveAndFlush(captured.capture());
        var saved = captured.getValue();
        assertThat(saved.getConnection().getWorkspace()).isSameAs(workspace);
        assertThat(saved.getConnection().getExternalAccountId()).isEqualTo("seller");
        assertThat(saved.getClientId()).isNull(); assertThat(saved.getClientSecret()).isNull();
        assertThat(saved.getCredentialSource()).isEqualTo(NaverCredentialSource.SOLUTION);
        assertThat(saved.getTokenType()).isEqualTo(NaverTokenType.SELLER);
        assertThat(saved.getSolutionSubscriptionId()).isEqualTo(7L);
        assertThat(saved.getCredentialVersion()).isEqualTo(1);
    }

    @Test void missingTokenCannotBeSavedAsConnected() {
        assertThatThrownBy(() -> bridge.save(10L,1L,20L,"application",7L,2L,
                new NaverCommerceClient.IssuedToken(null,SeoulDateTimes.now().plusHours(1)),account()))
                .isInstanceOf(ApiException.class);
        verify(connections,never()).saveAndFlush(any()); verify(details,never()).saveAndFlush(any());
    }

    @Test void reconnectLookupChecksWorkspaceOwnerAndProviderWithoutRevealingOtherWorkspaceSeller() {
        assertThat(bridge.reconnectAccountUid(10L,1L,20L)).isEqualTo("seller");
        verify(permissions).requireOwner(10L,1L); verify(manager).refresh(connection);
        assertThatThrownBy(() -> bridge.reconnectAccountUid(11L,1L,20L)).isInstanceOf(ApiException.class);
        connection.setProviderType(ProviderType.META);
        assertThatThrownBy(() -> bridge.reconnectAccountUid(10L,1L,20L)).isInstanceOf(ApiException.class);
    }

    private Long save(Long reconnect) { return bridge.save(10L,1L,reconnect,"application",7L,2L,
            new NaverCommerceClient.IssuedToken("verified-token",SeoulDateTimes.now().plusHours(1)),account()); }
    private NaverCommerceClient.SellerAccount account() { return new NaverCommerceClient.SellerAccount("seller-login","seller"); }
}
