package com.orinan.api.domain.navercommerce.service;

import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.navercommerce.controller.model.NaverStoreResponse.Store;
import com.orinan.api.domain.platformconnection.controller.model.PlatformAssetResponse;
import com.orinan.api.domain.platformconnection.controller.model.PlatformConnectionResponse;
import com.orinan.api.domain.platformconnection.service.PlatformConnectionService;
import com.orinan.db.naverasset.NaverAssetEntity;
import com.orinan.db.naverasset.NaverAssetRepository;
import com.orinan.db.platformasset.PlatformAssetEntity;
import com.orinan.db.platformasset.PlatformAssetRepository;
import com.orinan.db.platformasset.enums.AssetType;
import com.orinan.db.platformasset.enums.PlatformType;
import com.orinan.db.platformconnection.PlatformConnectionEntity;
import com.orinan.db.platformconnection.PlatformConnectionRepository;
import com.orinan.db.platformconnection.enums.ProviderType;
import com.orinan.db.workspace.WorkspaceEntity;
import com.orinan.db.workspace.WorkspaceRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class NaverStoreAccessServiceTest {
    private final PlatformConnectionService permissions = mock(PlatformConnectionService.class);
    private final PlatformAssetRepository assets = mock(PlatformAssetRepository.class);
    private final NaverAssetRepository details = mock(NaverAssetRepository.class);
    private final PlatformConnectionRepository connections = mock(PlatformConnectionRepository.class);
    private final WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final NaverStoreAccessService service = new NaverStoreAccessService(
            permissions, assets, details, connections, workspaces, entityManager);
    private WorkspaceEntity workspace;
    private PlatformAssetEntity asset;
    private PlatformConnectionEntity connection;
    private NaverAssetEntity detail;

    @BeforeEach
    void setUp() {
        workspace = WorkspaceEntity.builder().id(10L).name("워크스페이스").build();
        connection = PlatformConnectionEntity.builder().id(20L).workspace(workspace)
                .providerType(ProviderType.NAVER).accountName("판매자 계정").requiresReauth(false).build();
        asset = PlatformAssetEntity.builder().id(30L).workspaceId(10L).connectionId(20L)
                .platformType(PlatformType.NAVER_SMART_STORE).assetType(AssetType.STORE)
                .externalId("123456").name("내 스토어").build();
        detail = NaverAssetEntity.builder().assetId(30L).asset(asset).channelType("STOREFARM")
                .channelUrl("https://smartstore.naver.com/example").build();
        when(workspaces.findById(10L)).thenReturn(Optional.of(workspace));
        when(assets.findByIdAndWorkspaceId(30L, 10L)).thenReturn(Optional.of(asset));
        when(connections.findByIdAndWorkspaceId(20L, 10L)).thenReturn(Optional.of(connection));
        when(details.findById(30L)).thenReturn(Optional.of(detail));
    }

    @Test
    void workspaceMemberCanReadSavedStoreAfterRefreshingOwnershipAndMetadata() {
        assertThat(service.get(10L, 30L, 2L)).isEqualTo(new Store(30L, 20L, "123456", "내 스토어",
                "https://smartstore.naver.com/example", "판매자 계정", false));

        var order = inOrder(entityManager, permissions, assets, connections, details);
        order.verify(entityManager).refresh(workspace);
        order.verify(permissions).requireMember(10L, 2L);
        order.verify(assets).findByIdAndWorkspaceId(30L, 10L);
        order.verify(entityManager).refresh(asset);
        order.verify(connections).findByIdAndWorkspaceId(20L, 10L);
        order.verify(entityManager).refresh(connection);
        order.verify(details).findById(30L);
        order.verify(entityManager).refresh(detail);
        verify(permissions, never()).requireOwner(anyLong(), anyLong());
    }

    @Test
    void deniedMemberCannotLoadAnySavedAssetOrListConnections() {
        doThrow(new ApiException(ApiCode.BAD_REQUEST, "접근 권한이 없습니다."))
                .when(permissions).requireMember(10L, 2L);

        assertThatThrownBy(() -> service.get(10L, 30L, 2L)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.list(10L, 2L)).isInstanceOf(ApiException.class);

        verifyNoInteractions(assets, connections, details);
        verify(permissions, never()).findAll(anyLong(), anyLong());
    }

    @Test
    void anotherWorkspaceOrUnselectedExternalChannelCannotBeUsedAsAnInternalAssetId() {
        assertThatThrownBy(() -> service.get(99L, 30L, 2L)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.get(10L, 123456L, 2L)).isInstanceOf(ApiException.class);
        verify(assets).findByIdAndWorkspaceId(123456L, 10L);
        verifyNoInteractions(connections, details);

        // A stale managed asset must be rechecked even if the scoped repository lookup succeeded.
        doAnswer(invocation -> { asset.setWorkspaceId(99L); return null; }).when(entityManager).refresh(asset);
        assertThatThrownBy(() -> service.get(10L, 30L, 2L)).isInstanceOf(ApiException.class);
        verifyNoInteractions(connections, details);
    }

    @Test
    void rejectsWrongPlatformAssetKindDetachedConnectionAndMalformedChannelNumbers() {
        asset.setPlatformType(PlatformType.FACEBOOK);
        assertInvalid();
        asset.setPlatformType(PlatformType.NAVER_SMART_STORE);
        asset.setAssetType(AssetType.PAGE);
        assertInvalid();
        asset.setAssetType(AssetType.STORE);
        asset.setConnectionId(null);
        assertInvalid();
        asset.setConnectionId(20L);
        for (String invalid : new String[]{null, "", "0", "-1", "0123", "123x", " 123", "9223372036854775808"}) {
            asset.setExternalId(invalid);
            assertInvalid();
        }
        verifyNoInteractions(connections, details);
    }

    @Test
    void connectionMustStillBelongToWorkspaceAndNaverAfterRefresh() {
        when(connections.findByIdAndWorkspaceId(20L, 10L)).thenReturn(Optional.empty());
        assertInvalid();
        when(connections.findByIdAndWorkspaceId(20L, 10L)).thenReturn(Optional.of(connection));
        connection.setProviderType(ProviderType.META);
        assertInvalid();
        connection.setProviderType(ProviderType.NAVER);
        doAnswer(invocation -> {
            connection.setWorkspace(WorkspaceEntity.builder().id(99L).build());
            return null;
        }).when(entityManager).refresh(connection);
        assertInvalid();
        verifyNoInteractions(details);
    }

    @Test
    void missingOrRefreshedShoppingWindowMetadataCannotBeReadAsSmartStore() {
        when(details.findById(30L)).thenReturn(Optional.empty());
        assertInvalid();
        when(details.findById(30L)).thenReturn(Optional.of(detail));
        doAnswer(invocation -> { detail.setChannelType("WINDOW"); return null; }).when(entityManager).refresh(detail);
        assertInvalid();
        verify(entityManager).refresh(detail);
    }

    @Test
    void remoteResultsAreRejectedAfterChannelConnectionAuthenticationOrMembershipChanges() {
        Store expected = service.get(10L, 30L, 2L);
        service.requireUnchanged(10L, 2L, expected);
        asset.setExternalId("654321");
        assertThatThrownBy(() -> service.requireUnchanged(10L, 2L, expected)).isInstanceOf(ApiException.class);
        asset.setExternalId("123456");
        asset.setConnectionId(21L);
        var replacement = PlatformConnectionEntity.builder().id(21L).workspace(workspace)
                .providerType(ProviderType.NAVER).accountName("새 계정").requiresReauth(false).build();
        when(connections.findByIdAndWorkspaceId(21L, 10L)).thenReturn(Optional.of(replacement));
        assertThatThrownBy(() -> service.requireUnchanged(10L, 2L, expected)).isInstanceOf(ApiException.class);
        asset.setConnectionId(20L);
        connection.setRequiresReauth(true);
        assertThatThrownBy(() -> service.requireUnchanged(10L, 2L, expected)).isInstanceOf(ApiException.class);
        connection.setRequiresReauth(false);
        doThrow(new ApiException(ApiCode.BAD_REQUEST, "멤버 권한이 변경되었습니다."))
                .when(permissions).requireMember(10L, 2L);
        assertThatThrownBy(() -> service.requireUnchanged(10L, 2L, expected)).isInstanceOf(ApiException.class);
    }

    @Test
    void listOnlyIncludesSavedSmartStoresAndPreservesBothComputedAndFreshReauthFlags() {
        var selected = new PlatformAssetResponse(30L, "123456", "내 스토어", PlatformType.NAVER_SMART_STORE, AssetType.STORE, null);
        var other = new PlatformAssetResponse(31L, "654321", "광고 계정", PlatformType.NAVER_ADS, AssetType.AD_ACCOUNT, null);
        var meta = new PlatformConnectionResponse(22L, 10L, ProviderType.META, "meta", "메타", false, null, List.of(selected));
        var naver = new PlatformConnectionResponse(20L, 10L, ProviderType.NAVER, "seller", "판매자", false, null, List.of(selected, other));
        when(permissions.findAll(10L, 2L)).thenReturn(List.of(meta, naver));
        // Simulate a revocation committed after the initial connection list was read.
        doAnswer(invocation -> { connection.setRequiresReauth(true); return null; }).when(entityManager).refresh(connection);
        assertThat(service.list(10L, 2L)).singleElement().satisfies(store -> {
            assertThat(store.assetId()).isEqualTo(30L);
            assertThat(store.requiresReauth()).isTrue();
        });
        verify(assets, never()).findByIdAndWorkspaceId(31L, 10L);

        doAnswer(invocation -> { connection.setRequiresReauth(false); return null; }).when(entityManager).refresh(connection);
        when(permissions.findAll(10L, 2L)).thenReturn(List.of(new PlatformConnectionResponse(20L, 10L,
                ProviderType.NAVER, "seller", "판매자", true, null, List.of(selected))));
        assertThat(service.list(10L, 2L)).singleElement().satisfies(store -> assertThat(store.requiresReauth()).isTrue());
    }

    private void assertInvalid() {
        assertThatThrownBy(() -> service.get(10L, 30L, 2L)).isInstanceOf(ApiException.class);
    }
}
