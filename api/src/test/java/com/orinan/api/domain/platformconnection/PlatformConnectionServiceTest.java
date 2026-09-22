package com.orinan.api.domain.platformconnection;

import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.exception.ApiException;
import com.orinan.api.common.time.SeoulDateTimes;
import com.orinan.api.domain.platformconnection.meta.MetaGraphClient.AuthorizedAccount;
import com.orinan.api.domain.platformconnection.meta.MetaGraphClient.DiscoveredAsset;
import com.orinan.api.domain.platformconnection.service.PlatformConnectionService;
import com.orinan.api.domain.user.exception.UserErrorCode;
import com.orinan.api.domain.user.service.UserService;
import com.orinan.api.domain.workspace.service.WorkspaceService;
import com.orinan.api.domain.workspacemember.service.WorkspaceMemberService;
import com.orinan.db.metaasset.MetaAssetEntity;
import com.orinan.db.metaasset.MetaAssetRepository;
import com.orinan.db.metaconnection.MetaConnectionEntity;
import com.orinan.db.metaconnection.MetaConnectionRepository;
import com.orinan.db.naverconnection.NaverConnectionEntity;
import com.orinan.db.naverconnection.NaverConnectionRepository;
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
import com.orinan.db.workspacemember.WorkspaceMemberId;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class PlatformConnectionServiceTest {

    private final PlatformConnectionRepository connections = mock(PlatformConnectionRepository.class);
    private final MetaConnectionRepository metaConnections = mock(MetaConnectionRepository.class);
    private final PlatformAssetRepository assets = mock(PlatformAssetRepository.class);
    private final MetaAssetRepository metaAssets = mock(MetaAssetRepository.class);
    private final WorkspaceService workspaceService = mock(WorkspaceService.class);
    private final WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
    private final WorkspaceMemberService members = mock(WorkspaceMemberService.class);
    private final UserService users = mock(UserService.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final NaverConnectionRepository naverConnections = mock(NaverConnectionRepository.class);
    private final PlatformConnectionService service = new PlatformConnectionService(
            connections, metaConnections, assets, metaAssets, workspaceService, workspaces, members, users, entityManager, naverConnections);
    private final WorkspaceEntity workspace = WorkspaceEntity.builder().id(10L).name("광고 워크스페이스")
            .user(UserEntity.builder().id(1L).build()).build();

    @Test
    void naverListingShowsRenewableExpiryAndFlagsMissingCredentials() {
        var connection = connection();
        connection.setProviderType(ProviderType.NAVER);
        var expiredAt = SeoulDateTimes.now().minusMinutes(1);
        when(workspaceService.findByIdWithThrow(10L)).thenReturn(workspace);
        when(members.exists(new WorkspaceMemberId(10L, 2L))).thenReturn(true);
        when(connections.findAllByWorkspaceIdOrderByIdDesc(10L)).thenReturn(List.of(connection));
        when(naverConnections.findById(20L)).thenReturn(
                Optional.of(NaverConnectionEntity.builder().expiresAt(expiredAt).build()), Optional.empty());

        var response = service.findAll(10L, 2L).get(0);
        assertThat(response.expiresAt()).isEqualTo(expiredAt);
        assertThat(response.requiresReauth()).isFalse();
        assertThat(service.findAll(10L, 2L).get(0).requiresReauth()).isTrue();
        verifyNoInteractions(metaConnections);
    }

    @Test
    void membersCannotStartAuthorizationOrReplaceWorkspaceCredentials() {
        when(workspaceService.findByIdWithThrow(10L)).thenReturn(workspace);
        when(workspaces.findByIdForUpdate(10L)).thenReturn(Optional.of(workspace));
        when(members.exists(new WorkspaceMemberId(10L, 2L))).thenReturn(true);

        assertThatThrownBy(() -> service.requireOwner(10L, 2L))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.getCodeIfs()).isEqualTo(UserErrorCode.USER_PERMISSION_DENY));
        assertThatThrownBy(() -> service.saveMetaConnection(10L, 2L,
                new AuthorizedAccount("another-meta-user", "다른 계정", "replacement-token",
                        SeoulDateTimes.now().plusDays(30), "ads_read")))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.getCodeIfs()).isEqualTo(UserErrorCode.USER_PERMISSION_DENY));
        verifyNoInteractions(connections, metaConnections, assets, metaAssets);
    }

    @Test
    void acceptedMemberUsesTheSameWorkspaceCredentialAsTheOwner() {
        var connection = connection();
        var meta = credentials(connection);
        stubReadableConnection(connection, meta);
        when(members.exists(new WorkspaceMemberId(10L, 2L))).thenReturn(true);

        var ownerCredential = service.getMetaConnection(10L, 20L, 1L);
        var memberCredential = service.getMetaConnection(10L, 20L, 2L);

        assertThat(memberCredential).isSameAs(ownerCredential);
        assertThat(memberCredential.getAccessToken()).isEqualTo("current-token");
        assertThat(memberCredential.getConnection().getWorkspace().getId()).isEqualTo(10L);
        verify(metaConnections, times(2)).findById(20L);
    }

    @Test
    void outsiderCannotReadCredentialsOrSaveAssets() {
        when(workspaceService.findByIdWithThrow(10L)).thenReturn(workspace);
        when(workspaces.findByIdForUpdate(10L)).thenReturn(Optional.of(workspace));

        assertThatThrownBy(() -> service.getMetaConnection(10L, 20L, 3L))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.getCodeIfs()).isEqualTo(UserErrorCode.USER_PERMISSION_DENY));
        assertThatThrownBy(() -> service.saveMetaAssets(10L, 20L, 3L, "current-token", List.of()))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.getCodeIfs()).isEqualTo(UserErrorCode.USER_PERMISSION_DENY));
        verifyNoInteractions(connections, metaConnections, assets, metaAssets);
    }

    @Test
    void removedMemberCannotReadThePreviouslySharedCredentialAgain() {
        var connection = connection();
        stubReadableConnection(connection, credentials(connection));
        when(members.exists(new WorkspaceMemberId(10L, 2L))).thenReturn(true, false);

        service.getMetaConnection(10L, 20L, 2L);

        assertThatThrownBy(() -> service.getMetaConnection(10L, 20L, 2L))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.getCodeIfs()).isEqualTo(UserErrorCode.USER_PERMISSION_DENY));
        verify(connections, times(1)).findByIdAndWorkspaceId(20L, 10L);
        verify(metaConnections, times(1)).findById(20L);
    }

    @Test
    void memberCanListConnectionsAndLosesAccessAfterRemoval() {
        var connection = connection();
        var meta = credentials(connection);
        when(workspaceService.findByIdWithThrow(10L)).thenReturn(workspace);
        when(members.exists(new WorkspaceMemberId(10L, 2L))).thenReturn(true, false);
        when(connections.findAllByWorkspaceIdOrderByIdDesc(10L)).thenReturn(List.of(connection));
        when(metaConnections.findById(20L)).thenReturn(Optional.of(meta));

        var response = service.findAll(10L, 2L);

        assertThat(response).hasSize(1);
        assertThat(response.get(0).id()).isEqualTo(20L);
        assertThat(response.get(0).requiresReauth()).isFalse();
        assertThatThrownBy(() -> service.findAll(10L, 2L))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.getCodeIfs()).isEqualTo(UserErrorCode.USER_PERMISSION_DENY));
        verify(connections, times(1)).findAllByWorkspaceIdOrderByIdDesc(10L);
    }

    @Test
    void ownerCannotReadAConnectionFromAnotherWorkspace() {
        when(workspaceService.findByIdWithThrow(10L)).thenReturn(workspace);
        when(connections.findByIdAndWorkspaceId(99L, 10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getMetaConnection(10L, 99L, 1L))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.getCodeIfs()).isEqualTo(ApiCode.BAD_REQUEST));
        verifyNoInteractions(metaConnections, assets, metaAssets);
    }

    @Test
    void memberCannotReadAConnectionFromAnotherWorkspace() {
        when(workspaceService.findByIdWithThrow(10L)).thenReturn(workspace);
        when(members.exists(new WorkspaceMemberId(10L, 2L))).thenReturn(true);
        when(connections.findByIdAndWorkspaceId(99L, 10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getMetaConnection(10L, 99L, 2L))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.getCodeIfs()).isEqualTo(ApiCode.BAD_REQUEST));
        verifyNoInteractions(metaConnections, assets, metaAssets);
    }

    @Test
    void membershipRemovedDuringDiscoveryPreventsSavingAnyAssets() {
        var connection = connection();
        var meta = credentials(connection);
        stubReadableConnection(connection, meta);
        when(workspaces.findByIdForUpdate(10L)).thenReturn(Optional.of(workspace));
        when(members.exists(new WorkspaceMemberId(10L, 2L))).thenReturn(true, false);
        var credentialUsedForDiscovery = service.getMetaConnection(10L, 20L, 2L);

        assertThatThrownBy(() -> service.saveMetaAssets(10L, 20L, 2L,
                credentialUsedForDiscovery.getAccessToken(), List.of(new DiscoveredAsset("instagram-123",
                        "선택한 프로필", PlatformType.INSTAGRAM, AssetType.PROFILE, "page-456"))))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.getCodeIfs()).isEqualTo(UserErrorCode.USER_PERMISSION_DENY));

        verify(connections, times(1)).findByIdAndWorkspaceId(20L, 10L);
        verify(metaConnections, times(1)).findById(20L);
        verifyNoInteractions(assets, metaAssets);
    }

    @Test
    void acceptedMemberCanSaveAssetsUsingTheWorkspaceToken() {
        var connection = connection();
        var meta = credentials(connection);
        stubReadableConnection(connection, meta);
        when(workspaces.findByIdForUpdate(10L)).thenReturn(Optional.of(workspace));
        when(members.exists(new WorkspaceMemberId(10L, 2L))).thenReturn(true);
        when(assets.saveAndFlush(any(PlatformAssetEntity.class))).thenAnswer(invocation -> {
            PlatformAssetEntity saved = invocation.getArgument(0);
            saved.setId(30L);
            return saved;
        });

        service.saveMetaAssets(10L, 20L, 2L, "current-token",
                List.of(new DiscoveredAsset("instagram-123", "공유 프로필",
                        PlatformType.INSTAGRAM, AssetType.PROFILE, "page-456")));

        verify(assets).saveAndFlush(argThat(asset -> asset.getWorkspaceId().equals(10L)
                && asset.getConnectionId().equals(20L) && asset.getExternalId().equals("instagram-123")));
        verify(metaAssets).saveAndFlush(argThat(asset -> asset.getAsset().getId().equals(30L)
                && asset.getFacebookPageId().equals("page-456")));
        verify(users).findByIdAndStatusWithThrow(2L, UserStatus.REGISTERED);
        verify(metaConnections, never()).saveAndFlush(any());
    }

    @Test
    void expiredOrMissingExpiryRequiresReauthorizationBeforeUsingCredentials() {
        var connection = connection();
        var meta = credentials(connection);
        stubReadableConnection(connection, meta);
        meta.setExpiresAt(SeoulDateTimes.now().minusMinutes(1));

        assertThatThrownBy(() -> service.getMetaConnection(10L, 20L, 1L))
                .isInstanceOf(ApiException.class).hasMessage("Meta 계정을 다시 연결해 주세요.");
        meta.setExpiresAt(null);
        assertThatThrownBy(() -> service.getMetaConnection(10L, 20L, 1L))
                .isInstanceOf(ApiException.class).hasMessage("Meta 계정을 다시 연결해 주세요.");
        verifyNoInteractions(assets, metaAssets);
    }

    @Test
    void reauthorizationUpdatesTheExistingConnectionAndCredentialRows() {
        var connection = connection();
        connection.setRequiresReauth(true);
        var meta = credentials(connection);
        var expiry = SeoulDateTimes.now().plusDays(30);
        when(workspaces.findByIdForUpdate(10L)).thenReturn(Optional.of(workspace));
        when(connections.findByWorkspaceIdAndProviderTypeAndExternalAccountId(10L, ProviderType.META, "meta-user-123"))
                .thenReturn(Optional.of(connection));
        when(connections.saveAndFlush(connection)).thenReturn(connection);
        when(metaConnections.findById(20L)).thenReturn(Optional.of(meta));

        var response = service.saveMetaConnection(10L, 1L,
                new AuthorizedAccount("meta-user-123", "변경된 계정명", "new-access-token", expiry, "ads_read"));

        assertThat(response.id()).isEqualTo(20L);
        assertThat(response.requiresReauth()).isFalse();
        assertThat(meta.getAccessToken()).isEqualTo("new-access-token");
        assertThat(meta.getExpiresAt()).isEqualTo(expiry);
        verify(connections).saveAndFlush(same(connection));
        verify(metaConnections).saveAndFlush(same(meta));
        verify(users).findByIdAndStatusWithThrow(1L, UserStatus.REGISTERED);
    }

    @Test
    void tokenChangedAfterDiscoveryRejectsAssetSaveBeforeWritingAnyAsset() {
        var connection = connection();
        var meta = credentials(connection);
        stubReadableConnection(connection, meta);
        when(workspaces.findByIdForUpdate(10L)).thenReturn(Optional.of(workspace));

        assertThatThrownBy(() -> service.saveMetaAssets(10L, 20L, 1L, "outdated-token",
                List.of(new DiscoveredAsset("instagram-123", "새 프로필", PlatformType.INSTAGRAM,
                        AssetType.PROFILE, "page-456"))))
                .isInstanceOf(ApiException.class).hasMessageContaining("연결 정보가 변경되었습니다");
        verifyNoInteractions(assets, metaAssets);
    }

    @Test
    void refreshesCachedCredentialsAfterLockToDetectConcurrentReauthorization() {
        var connection = connection();
        var cachedMeta = credentials(connection);
        var tokenUsedForDiscovery = cachedMeta.getAccessToken();
        stubReadableConnection(connection, cachedMeta);
        when(workspaces.findByIdForUpdate(10L)).thenReturn(Optional.of(workspace));
        doAnswer(invocation -> {
            cachedMeta.setAccessToken("token-from-concurrent-reauthorization");
            return null;
        }).when(entityManager).refresh(cachedMeta);

        assertThatThrownBy(() -> service.saveMetaAssets(10L, 20L, 1L, tokenUsedForDiscovery,
                List.of(new DiscoveredAsset("instagram-123", "새 프로필", PlatformType.INSTAGRAM,
                        AssetType.PROFILE, "page-456"))))
                .isInstanceOf(ApiException.class).hasMessageContaining("연결 정보가 변경되었습니다");

        assertThat(cachedMeta.getAccessToken()).isNotEqualTo(tokenUsedForDiscovery);
        var ordered = inOrder(workspaces, entityManager);
        ordered.verify(workspaces).findByIdForUpdate(10L);
        ordered.verify(entityManager).refresh(workspace);
        ordered.verify(entityManager).refresh(connection);
        ordered.verify(entityManager).refresh(cachedMeta);
        verifyNoInteractions(assets, metaAssets);
    }

    @Test
    void savingAnExistingAssetAgainUpdatesTheSameAssetAndExtension() {
        var connection = connection();
        var meta = credentials(connection);
        stubReadableConnection(connection, meta);
        when(workspaces.findByIdForUpdate(10L)).thenReturn(Optional.of(workspace));
        var asset = PlatformAssetEntity.builder().id(30L).workspaceId(10L).connectionId(20L)
                .platformType(PlatformType.INSTAGRAM).assetType(AssetType.PROFILE)
                .externalId("instagram-123").name("이전 프로필명").build();
        var detail = MetaAssetEntity.builder().assetId(30L).asset(asset).facebookPageId("previous-page").build();
        when(assets.findByConnectionIdAndPlatformTypeAndAssetTypeAndExternalId(
                20L, PlatformType.INSTAGRAM, AssetType.PROFILE, "instagram-123"))
                .thenReturn(Optional.of(asset));
        when(assets.saveAndFlush(asset)).thenReturn(asset);
        when(metaAssets.findById(30L)).thenReturn(Optional.of(detail));
        when(assets.findAllByConnectionIdOrderByIdAsc(20L)).thenReturn(List.of(asset));
        var selected = List.of(new DiscoveredAsset("instagram-123", "최신 프로필명",
                PlatformType.INSTAGRAM, AssetType.PROFILE, "page-456"));

        service.saveMetaAssets(10L, 20L, 1L, meta.getAccessToken(), selected);
        var response = service.saveMetaAssets(10L, 20L, 1L, meta.getAccessToken(), selected);

        assertThat(response).hasSize(1);
        assertThat(response.get(0).id()).isEqualTo(30L);
        assertThat(response.get(0).name()).isEqualTo("최신 프로필명");
        assertThat(response.get(0).facebookPageId()).isEqualTo("page-456");
        verify(assets, times(2)).saveAndFlush(same(asset));
        verify(metaAssets, times(2)).saveAndFlush(same(detail));
    }

    private void stubReadableConnection(PlatformConnectionEntity connection, MetaConnectionEntity meta) {
        when(workspaceService.findByIdWithThrow(10L)).thenReturn(workspace);
        when(connections.findByIdAndWorkspaceId(20L, 10L)).thenReturn(Optional.of(connection));
        when(metaConnections.findById(20L)).thenReturn(Optional.of(meta));
    }

    private PlatformConnectionEntity connection() {
        return PlatformConnectionEntity.builder().id(20L).workspace(workspace)
                .providerType(ProviderType.META).externalAccountId("meta-user-123")
                .accountName("Meta 운영자").requiresReauth(false).build();
    }

    private MetaConnectionEntity credentials(PlatformConnectionEntity connection) {
        return MetaConnectionEntity.builder().connectionId(connection.getId()).connection(connection)
                .accessToken("current-token").expiresAt(SeoulDateTimes.now().plusDays(30))
                .grantedScopes("ads_read").build();
    }
}
