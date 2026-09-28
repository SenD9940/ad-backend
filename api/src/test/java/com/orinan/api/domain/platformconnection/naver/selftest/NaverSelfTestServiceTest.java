package com.orinan.api.domain.platformconnection.naver.selftest;

import com.orinan.api.common.exception.ApiException;
import com.orinan.api.common.time.SeoulDateTimes;
import com.orinan.api.domain.platformconnection.naver.NaverCommerceClient;
import com.orinan.api.domain.platformconnection.service.PlatformConnectionService;
import com.orinan.api.domain.user.service.UserService;
import com.orinan.db.naverconnection.NaverConnectionEntity;
import com.orinan.db.naverconnection.NaverConnectionRepository;
import com.orinan.db.naverconnection.enums.NaverCredentialSource;
import com.orinan.db.naverconnection.enums.NaverTokenType;
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
import org.springframework.mock.env.MockEnvironment;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class NaverSelfTestServiceTest {
    private final NaverSelfTestProperties properties = new NaverSelfTestProperties();
    private final MockEnvironment environment = new MockEnvironment();
    private final WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
    private final PlatformConnectionRepository connections = mock(PlatformConnectionRepository.class);
    private final NaverConnectionRepository details = mock(NaverConnectionRepository.class);
    private final PlatformConnectionService platform = mock(PlatformConnectionService.class);
    private final UserService users = mock(UserService.class);
    private final EntityManager manager = mock(EntityManager.class);
    private final NaverSelfTestPolicy policy = new NaverSelfTestPolicy(properties,environment,platform,workspaces,manager);
    private final NaverSelfTestService service = new NaverSelfTestService(policy,workspaces,connections,details,platform,users,manager);
    private final WorkspaceEntity workspace = WorkspaceEntity.builder().id(1L).user(UserEntity.builder().id(2L).build()).build();
    private final NaverSelfTestPolicy.Credentials credentials = new NaverSelfTestPolicy.Credentials("app","secret");
    private final NaverCommerceClient.IssuedToken token = new NaverCommerceClient.IssuedToken("new-token",SeoulDateTimes.now().plusHours(1));
    private final NaverCommerceClient.SellerAccount account = new NaverCommerceClient.SellerAccount("store-login","store-uid");

    @BeforeEach void setUp() {
        environment.setActiveProfiles("local"); properties.setAppId("app"); properties.setAppSecret("secret");
        properties.getSelfTest().setEnabled(true); properties.getSelfTest().setWorkspaceId(1L);
        when(workspaces.findByIdForUpdate(1L)).thenReturn(Optional.of(workspace));
        when(connections.saveAndFlush(any())).thenAnswer(invocation -> {
            PlatformConnectionEntity value = invocation.getArgument(0); if(value.getId()==null) value.setId(7L); return value;
        });
    }

    @Test void persistsVerifiedStoreAsNormalSelfConnectionForExistingTokenRefreshAndOauthConversion() {
        save();
        var captured = ArgumentCaptor.forClass(NaverConnectionEntity.class); verify(details).saveAndFlush(captured.capture());
        var saved = captured.getValue();
        assertThat(saved.getConnection().getExternalAccountId()).isEqualTo("store-uid");
        assertThat(saved.getConnection().getAccountName()).isEqualTo("store-login");
        assertThat(saved.getConnection().getWorkspace()).isSameAs(workspace);
        assertThat(saved.getCredentialSource()).isEqualTo(NaverCredentialSource.MANUAL);
        assertThat(saved.getTokenType()).isEqualTo(NaverTokenType.SELF); assertThat(saved.getAccountId()).isNull();
        assertThat(saved.getClientId()).isEqualTo("app"); assertThat(saved.getClientSecret()).isEqualTo("secret");
        assertThat(saved.getAccessToken()).isEqualTo("new-token"); assertThat(saved.getCredentialVersion()).isEqualTo(1);
        assertThat(saved.getApplicationRef()).isNull(); assertThat(saved.getSolutionSubscriptionId()).isNull();
        verify(users).findByIdAndStatusWithThrow(2L,UserStatus.REGISTERED);
        verify(platform).findById(1L,7L,2L);
    }

    @Test void reconnectUsesSameConnectionAndPreservesAssetReferences() {
        var existing = existing(NaverCredentialSource.MANUAL);
        save();
        assertThat(existing.getConnectionId()).isEqualTo(8L);
        assertThat(existing.getCredentialVersion()).isEqualTo(5);
        assertThat(existing.getConnection().getRequiresReauth()).isFalse();
        verify(connections).saveAndFlush(same(existing.getConnection())); verify(details).saveAndFlush(same(existing));
        verify(platform).findById(1L,8L,2L);
    }

    @Test void cannotDowngradeSolutionConnectionOrChangeItsCredentials() {
        var existing = existing(NaverCredentialSource.SOLUTION);
        assertThatThrownBy(this::save).isInstanceOf(ApiException.class);
        assertThat(existing.getCredentialSource()).isEqualTo(NaverCredentialSource.SOLUTION);
        assertThat(existing.getAccessToken()).isEqualTo("old-token");
        verify(connections,never()).saveAndFlush(any()); verify(details,never()).saveAndFlush(any());
    }

    @Test void ownershipLossDuringRemoteAuthenticationStopsPersistenceAfterLockedRefresh() {
        doAnswer(invocation -> { workspace.setUser(UserEntity.builder().id(3L).build()); return null; })
                .when(manager).refresh(workspace);
        assertThatThrownBy(this::save).isInstanceOf(ApiException.class);
        verify(manager).refresh(workspace); verifyNoInteractions(connections,details,users);
    }

    @Test void appRotationDisablementAndProductionProfileDuringAuthenticationStopPersistence() {
        properties.setAppSecret("rotated"); assertThatThrownBy(this::save).isInstanceOf(ApiException.class);
        properties.setAppSecret("secret"); properties.getSelfTest().setEnabled(false);
        assertThatThrownBy(this::save).isInstanceOf(ApiException.class);
        properties.getSelfTest().setEnabled(true); environment.setActiveProfiles("local","prod");
        assertThatThrownBy(this::save).isInstanceOf(ApiException.class);
        verifyNoInteractions(connections,details,users);
    }

    @Test void expiredOrIncompleteRemoteResultCannotCreateConnectedRecord() {
        assertThatThrownBy(() -> service.save(1L,2L,credentials,
                new NaverCommerceClient.IssuedToken("expired",SeoulDateTimes.now().minusSeconds(1)),account)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.save(1L,2L,credentials,token,
                new NaverCommerceClient.SellerAccount("store-login",""))).isInstanceOf(ApiException.class);
        verifyNoInteractions(connections,details);
    }

    private NaverConnectionEntity existing(NaverCredentialSource source) {
        var connection = PlatformConnectionEntity.builder().id(8L).workspace(workspace).providerType(ProviderType.NAVER)
                .externalAccountId("store-uid").accountName("old-name").requiresReauth(true).build();
        var detail = NaverConnectionEntity.builder().connectionId(8L).connection(connection).credentialSource(source)
                .accessToken("old-token").credentialVersion(4).build();
        when(connections.findByWorkspaceIdAndProviderTypeAndExternalAccountId(1L,ProviderType.NAVER,"store-uid"))
                .thenReturn(Optional.of(connection));
        when(details.findById(8L)).thenReturn(Optional.of(detail));
        return detail;
    }

    private void save() { service.save(1L,2L,credentials,token,account); }
}
