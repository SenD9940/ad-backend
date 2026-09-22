package com.orinan.api.domain.platformconnection;

import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.exception.ApiException;
import com.orinan.api.common.time.SeoulDateTimes;
import com.orinan.api.domain.platformconnection.controller.model.NaverConnectRequest;
import com.orinan.api.domain.platformconnection.naver.NaverCommerceClient.Channel;
import com.orinan.api.domain.platformconnection.naver.NaverCommerceClient.IssuedToken;
import com.orinan.api.domain.platformconnection.naver.NaverCommerceClient.SellerAccount;
import com.orinan.api.domain.platformconnection.service.NaverConnectionService;
import com.orinan.api.domain.platformconnection.service.NaverConnectionService.Credentials;
import com.orinan.api.domain.platformconnection.service.PlatformConnectionService;
import com.orinan.api.domain.user.exception.UserErrorCode;
import com.orinan.api.domain.user.service.UserService;
import com.orinan.db.naverasset.NaverAssetEntity;
import com.orinan.db.naverasset.NaverAssetRepository;
import com.orinan.db.naverconnection.NaverConnectionEntity;
import com.orinan.db.naverconnection.NaverConnectionRepository;
import com.orinan.db.naverconnection.enums.NaverTokenType;
import com.orinan.db.platformasset.PlatformAssetEntity;
import com.orinan.db.platformasset.PlatformAssetRepository;
import com.orinan.db.platformasset.enums.AssetType;
import com.orinan.db.platformasset.enums.PlatformType;
import com.orinan.db.platformconnection.PlatformConnectionEntity;
import com.orinan.db.platformconnection.PlatformConnectionRepository;
import com.orinan.db.platformconnection.enums.ProviderType;
import com.orinan.db.user.UserEntity;
import com.orinan.db.user.enums.UserStatus;
import com.orinan.db.workspace.WorkspaceEntity;
import com.orinan.db.workspace.WorkspaceRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class NaverConnectionServiceTest {

    private final PlatformConnectionRepository connections = mock(PlatformConnectionRepository.class);
    private final NaverConnectionRepository naverConnections = mock(NaverConnectionRepository.class);
    private final PlatformAssetRepository assets = mock(PlatformAssetRepository.class);
    private final NaverAssetRepository naverAssets = mock(NaverAssetRepository.class);
    private final WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
    private final PlatformConnectionService platformConnections = mock(PlatformConnectionService.class);
    private final UserService users = mock(UserService.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final NaverConnectionService service = new NaverConnectionService(connections, naverConnections,
            assets, naverAssets, workspaces, platformConnections, users, entityManager);
    private final WorkspaceEntity workspace = WorkspaceEntity.builder().id(10L).name("광고 워크스페이스")
            .user(UserEntity.builder().id(1L).build()).build();

    @Test
    void lostOwnershipDuringAuthenticationRejectsCredentialPersistenceUnderWorkspaceLock() {
        when(workspaces.findByIdForUpdate(10L)).thenReturn(Optional.of(workspace));
        doThrow(new ApiException(UserErrorCode.USER_PERMISSION_DENY)).when(platformConnections).requireOwner(10L, 1L);

        assertThatThrownBy(() -> service.saveConnection(10L, 1L, request(), token("new-token"),
                new SellerAccount("seller-login", "seller-uid")))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.getCodeIfs()).isEqualTo(UserErrorCode.USER_PERMISSION_DENY));

        var ordered = inOrder(workspaces, entityManager, platformConnections);
        ordered.verify(workspaces).findByIdForUpdate(10L);
        ordered.verify(entityManager).refresh(workspace);
        ordered.verify(platformConnections).requireOwner(10L, 1L);
        verifyNoInteractions(connections, naverConnections, assets, naverAssets);
    }

    @Test
    void reconnectUpdatesTheExistingWorkspaceSellerAndCredentials() {
        var connection = connection();
        connection.setRequiresReauth(true);
        var detail = detail(connection);
        var request = request();
        var token = token("new-token");
        when(workspaces.findByIdForUpdate(10L)).thenReturn(Optional.of(workspace));
        when(connections.findByWorkspaceIdAndProviderTypeAndExternalAccountId(10L, ProviderType.NAVER, "seller-uid"))
                .thenReturn(Optional.of(connection));
        when(connections.saveAndFlush(connection)).thenReturn(connection);
        when(naverConnections.findById(20L)).thenReturn(Optional.of(detail));

        service.saveConnection(10L, 1L, request, token, new SellerAccount("updated-login", "seller-uid"));

        assertThat(connection.getRequiresReauth()).isFalse();
        assertThat(connection.getAccountName()).isEqualTo("updated-login");
        assertThat(detail.getClientSecret()).isEqualTo("new-secret");
        assertThat(detail.getAccessToken()).isEqualTo("new-token");
        assertThat(detail.getExpiresAt()).isEqualTo(token.expiresAt());
        verify(connections).saveAndFlush(same(connection));
        verify(naverConnections).saveAndFlush(same(detail));
        verify(users).findByIdAndStatusWithThrow(1L, UserStatus.REGISTERED);
    }

    @Test
    void memberReadsTheWorkspaceCredentialAndLosesAccessWhenRemoved() {
        var detail = stubConnection();
        doNothing().doThrow(new ApiException(UserErrorCode.USER_PERMISSION_DENY))
                .when(platformConnections).requireMember(10L, 2L);

        assertThat(service.getCredentials(10L, 20L, 2L).accessToken()).isEqualTo(detail.getAccessToken());
        assertThatThrownBy(() -> service.getCredentials(10L, 20L, 2L))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.getCodeIfs()).isEqualTo(UserErrorCode.USER_PERMISSION_DENY));

        verify(connections, times(1)).findByIdAndWorkspaceId(20L, 10L);
        verify(naverConnections, times(1)).findById(20L);
    }

    @Test
    void anotherWorkspaceOrAnotherProviderCannotExposeNaverCredentials() {
        assertThatThrownBy(() -> service.getCredentials(10L, 99L, 2L))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.getCodeIfs()).isEqualTo(ApiCode.BAD_REQUEST));
        var connection = connection();
        connection.setProviderType(ProviderType.META);
        when(connections.findByIdAndWorkspaceId(20L, 10L)).thenReturn(Optional.of(connection));

        assertThatThrownBy(() -> service.getCredentials(10L, 20L, 2L))
                .isInstanceOf(ApiException.class).hasMessage("네이버 연결이 아닙니다.");

        verifyNoInteractions(naverConnections);
    }

    @Test
    void explicitlyInvalidatedConnectionCannotBeUsedEvenWithUnexpiredToken() {
        var detail = stubConnection();
        detail.getConnection().setRequiresReauth(true);

        assertThatThrownBy(() -> service.getCredentials(10L, 20L, 2L))
                .isInstanceOf(ApiException.class).hasMessageContaining("다시 연결");

        verify(naverConnections, never()).findById(any());
    }

    @Test
    void concurrentReconnectCannotBeOverwrittenByARefreshUsingOldCredentials() {
        var detail = stubConnection();
        var expected = snapshot(detail);
        when(workspaces.findByIdForUpdate(10L)).thenReturn(Optional.of(workspace));
        doAnswer(invocation -> {
            detail.setClientSecret("owners-replacement-secret");
            detail.setAccessToken("owners-replacement-token");
            return null;
        }).when(entityManager).refresh(detail);

        assertThatThrownBy(() -> service.updateToken(10L, 20L, 2L, expected, token("stale-refresh-token")))
                .isInstanceOf(ApiException.class).hasMessageContaining("연결 정보가 변경되었습니다");

        assertThat(detail.getAccessToken()).isEqualTo("owners-replacement-token");
        verify(naverConnections, never()).saveAndFlush(any());
    }

    @Test
    void concurrentRefreshReusesTheAlreadySavedUnexpiredToken() {
        var detail = stubConnection();
        var expected = snapshot(detail);
        detail.setAccessToken("concurrently-renewed-token");
        detail.setExpiresAt(SeoulDateTimes.now().plusHours(3));
        when(workspaces.findByIdForUpdate(10L)).thenReturn(Optional.of(workspace));

        var current = service.updateToken(10L, 20L, 2L, expected, token("later-response-token"));

        assertThat(current.accessToken()).isEqualTo("concurrently-renewed-token");
        assertThat(current.expiresAt()).isEqualTo(detail.getExpiresAt());
        verify(naverConnections, never()).saveAndFlush(any());
    }

    @Test
    void concurrentTokenWithMissingExpiryDoesNotSuppressAValidRefresh() {
        var detail = stubConnection();
        var expected = snapshot(detail);
        detail.setAccessToken("concurrent-token-without-expiry");
        detail.setExpiresAt(null);
        var refreshed = token("verified-refreshed-token");
        when(workspaces.findByIdForUpdate(10L)).thenReturn(Optional.of(workspace));

        var current = service.updateToken(10L, 20L, 2L, expected, refreshed);

        assertThat(current.accessToken()).isEqualTo(refreshed.accessToken());
        assertThat(current.expiresAt()).isEqualTo(refreshed.expiresAt());
        verify(naverConnections).saveAndFlush(detail);
    }

    @Test
    void staleAuthenticationFailureDoesNotInvalidateNewlySavedCredentials() {
        var detail = stubConnection();
        var expected = snapshot(detail);
        detail.setAccessToken("concurrently-renewed-token");
        when(workspaces.findByIdForUpdate(10L)).thenReturn(Optional.of(workspace));

        service.markRequiresReauth(10L, 20L, 2L, expected);

        assertThat(detail.getConnection().getRequiresReauth()).isFalse();
        verify(connections, never()).saveAndFlush(any());
    }

    @Test
    void invalidCurrentTokenMarksOnlyItsWorkspaceConnectionForReauthorization() {
        var detail = stubConnection();
        when(workspaces.findByIdForUpdate(10L)).thenReturn(Optional.of(workspace));

        service.markRequiresReauth(10L, 20L, 2L, snapshot(detail));

        assertThat(detail.getConnection().getRequiresReauth()).isTrue();
        verify(connections).saveAndFlush(detail.getConnection());
        verify(naverConnections, never()).saveAndFlush(any());
    }

    @Test
    void lostMembershipBeforePersistenceRejectsSelectedChannelsWithoutWrites() {
        when(workspaces.findByIdForUpdate(10L)).thenReturn(Optional.of(workspace));
        doThrow(new ApiException(UserErrorCode.USER_PERMISSION_DENY)).when(platformConnections).requireMember(10L, 2L);
        var expected = snapshot(detail(connection()));

        assertThatThrownBy(() -> service.saveChannels(10L, 20L, 2L, expected, List.of(channel())))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.getCodeIfs()).isEqualTo(UserErrorCode.USER_PERMISSION_DENY));

        verifyNoInteractions(connections, naverConnections, assets, naverAssets);
    }

    @Test
    void changedTokenAfterDiscoveryRejectsSelectedChannelsWithoutWrites() {
        var detail = stubConnection();
        var expected = snapshot(detail);
        detail.setAccessToken("new-token-after-discovery");
        when(workspaces.findByIdForUpdate(10L)).thenReturn(Optional.of(workspace));

        assertThatThrownBy(() -> service.saveChannels(10L, 20L, 2L, expected, List.of(channel())))
                .isInstanceOf(ApiException.class).hasMessageContaining("연결 정보가 변경되었습니다");

        verifyNoInteractions(assets, naverAssets);
    }

    @Test
    void memberUpdatesExistingChannelAndExtensionWithVerifiedMetadata() {
        var detail = stubConnection();
        when(workspaces.findByIdForUpdate(10L)).thenReturn(Optional.of(workspace));
        var asset = PlatformAssetEntity.builder().id(30L).workspaceId(10L).connectionId(20L)
                .platformType(PlatformType.NAVER_SMART_STORE).assetType(AssetType.STORE)
                .externalId("123").name("이전 이름").build();
        var assetDetail = NaverAssetEntity.builder().assetId(30L).asset(asset)
                .channelType("STOREFARM").channelUrl("https://smartstore.naver.com/old").build();
        when(assets.findByConnectionIdAndPlatformTypeAndAssetTypeAndExternalId(
                20L, PlatformType.NAVER_SMART_STORE, AssetType.STORE, "123")).thenReturn(Optional.of(asset));
        when(assets.saveAndFlush(asset)).thenReturn(asset);
        when(naverAssets.findById(30L)).thenReturn(Optional.of(assetDetail));
        when(assets.findAllByConnectionIdOrderByIdAsc(20L)).thenReturn(List.of(asset));

        var response = service.saveChannels(10L, 20L, 2L, snapshot(detail), List.of(channel()));

        assertThat(response).hasSize(1);
        assertThat(response.get(0).assetId()).isEqualTo(30L);
        assertThat(response.get(0).name()).isEqualTo(channel().name());
        assertThat(response.get(0).url()).isEqualTo(channel().url());
        verify(assets).saveAndFlush(same(asset));
        verify(naverAssets).saveAndFlush(same(assetDetail));
        verify(users).findByIdAndStatusWithThrow(2L, UserStatus.REGISTERED);
    }

    private NaverConnectionEntity stubConnection() {
        var connection = connection();
        var detail = detail(connection);
        when(connections.findByIdAndWorkspaceId(20L, 10L)).thenReturn(Optional.of(connection));
        when(naverConnections.findById(20L)).thenReturn(Optional.of(detail));
        return detail;
    }

    private PlatformConnectionEntity connection() {
        return PlatformConnectionEntity.builder().id(20L).workspace(workspace).providerType(ProviderType.NAVER)
                .externalAccountId("seller-uid").accountName("seller-login").requiresReauth(false).build();
    }

    private NaverConnectionEntity detail(PlatformConnectionEntity connection) {
        return NaverConnectionEntity.builder().connectionId(20L).connection(connection)
                .clientId("app-id").clientSecret("app-secret").tokenType(NaverTokenType.SELF)
                .accessToken("saved-token").expiresAt(SeoulDateTimes.now().plusHours(2)).build();
    }

    private Credentials snapshot(NaverConnectionEntity detail) {
        return new Credentials(detail.getConnection().getExternalAccountId(), detail.getClientId(), detail.getClientSecret(),
                detail.getTokenType(), detail.getAccountId(), detail.getAccessToken(), detail.getExpiresAt());
    }

    private NaverConnectRequest request() {
        var request = new NaverConnectRequest();
        request.setClientId("new-app-id");
        request.setClientSecret("new-secret");
        return request;
    }

    private IssuedToken token(String token) {
        return new IssuedToken(token, SeoulDateTimes.now().plusHours(3));
    }

    private Channel channel() {
        return new Channel(123L, "STOREFARM", "확인된 스마트스토어", "https://smartstore.naver.com/verified");
    }
}
