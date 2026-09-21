package com.orinan.api.domain.platformconnection;

import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.platformconnection.business.PlatformConnectionBusiness;
import com.orinan.api.domain.platformconnection.controller.model.MetaAssetSelectRequest;
import com.orinan.api.domain.platformconnection.meta.MetaGraphClient;
import com.orinan.api.domain.platformconnection.meta.MetaProperties;
import com.orinan.api.domain.platformconnection.service.MetaOAuthStateService;
import com.orinan.api.domain.platformconnection.service.PlatformConnectionService;
import com.orinan.api.domain.user.exception.UserErrorCode;
import com.orinan.db.metaconnection.MetaConnectionEntity;
import com.orinan.db.platformasset.enums.AssetType;
import com.orinan.db.platformasset.enums.PlatformType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlatformConnectionBusinessTest {

    private final PlatformConnectionService service = mock(PlatformConnectionService.class);
    private final MetaGraphClient client = mock(MetaGraphClient.class);
    private final MetaOAuthStateService states = mock(MetaOAuthStateService.class);
    private final MetaProperties properties = mock(MetaProperties.class);
    private final PlatformConnectionBusiness business = new PlatformConnectionBusiness(service, client, states, properties);

    @Test
    void invalidStateAndDeniedConsentCannotExchangeOrSave() {
        when(states.consume("invalid", "cookie")).thenThrow(new ApiException(ApiCode.BAD_REQUEST));
        assertThatThrownBy(() -> business.completeMetaAuthorization("invalid", "cookie", "code", null))
                .isInstanceOf(ApiException.class);
        when(states.consume("valid", "cookie")).thenReturn(new MetaOAuthStateService.OAuthOwner(1L, 2L));
        assertThatThrownBy(() -> business.completeMetaAuthorization("valid", "cookie", null, "access_denied"))
                .isInstanceOf(ApiException.class);
        verifyNoInteractions(client, service);
    }

    @Test
    void callbackRechecksWorkspaceOwnerBeforeExchangingCode() {
        when(states.consume("state", "cookie")).thenReturn(new MetaOAuthStateService.OAuthOwner(1L, 2L));
        doThrow(new ApiException(ApiCode.BAD_REQUEST)).when(service).requireOwner(1L, 2L);
        assertThatThrownBy(() -> business.completeMetaAuthorization("state", "cookie", "code", null))
                .isInstanceOf(ApiException.class);
        verifyNoInteractions(client);
        verify(service, never()).saveMetaConnection(any(), any(), any());
    }

    @Test
    void memberCannotStartOAuthEvenThoughWorkspaceCredentialsAreShared() {
        doThrow(new ApiException(UserErrorCode.USER_PERMISSION_DENY)).when(service).requireOwner(1L, 2L);

        assertThatThrownBy(() -> business.startMetaAuthorization(1L, 2L))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.getCodeIfs()).isEqualTo(UserErrorCode.USER_PERMISSION_DENY));
        verifyNoInteractions(client, states, properties);
    }

    @Test
    void discoveryUsesTheWorkspaceTokenAndRechecksMembershipBeforeReturningAssets() {
        var available = List.of(availableAsset());
        when(service.getMetaConnection(1L, 3L, 2L)).thenReturn(meta());
        when(client.discoverAssets("private-token")).thenReturn(available);

        var response = business.discoverMetaAssets(1L, 3L, 2L);

        assertThat(response).isEqualTo(available);
        var ordered = inOrder(service, client);
        ordered.verify(service).getMetaConnection(1L, 3L, 2L);
        ordered.verify(client).discoverAssets("private-token");
        ordered.verify(service).requireMember(1L, 2L);
        verifyNoInteractions(states, properties);
    }

    @Test
    void membershipRemovedDuringRemoteDiscoveryPreventsReturningAssets() {
        when(service.getMetaConnection(1L, 3L, 2L)).thenReturn(meta());
        when(client.discoverAssets("private-token")).thenReturn(List.of(availableAsset()));
        doThrow(new ApiException(UserErrorCode.USER_PERMISSION_DENY)).when(service).requireMember(1L, 2L);

        assertThatThrownBy(() -> business.discoverMetaAssets(1L, 3L, 2L))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.getCodeIfs()).isEqualTo(UserErrorCode.USER_PERMISSION_DENY));
        verify(client).discoverAssets("private-token");
    }

    @Test
    void nonMemberCannotUseTheWorkspaceTokenForRemoteDiscoveryOrSelection() {
        when(service.getMetaConnection(1L, 3L, 2L))
                .thenThrow(new ApiException(UserErrorCode.USER_PERMISSION_DENY));

        assertThatThrownBy(() -> business.discoverMetaAssets(1L, 3L, 2L))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> business.selectMetaAssets(1L, 3L, 2L,
                new MetaAssetSelectRequest(List.of(selection("ig_1")))))
                .isInstanceOf(ApiException.class);
        verifyNoInteractions(client);
        verify(service, never()).saveMetaAssets(any(), any(), any(), any(), any());
    }

    @Test
    void selectingUnownedAssetFailsEntireRequestBeforeSavingAnything() {
        var legitimate = availableAsset();
        when(service.getMetaConnection(1L, 3L, 2L)).thenReturn(meta());
        when(client.discoverAssets("private-token")).thenReturn(List.of(legitimate));
        var request = new MetaAssetSelectRequest(List.of(selection("ig_1"), selection("another_users_asset")));
        assertThatThrownBy(() -> business.selectMetaAssets(1L, 3L, 2L, request))
                .isInstanceOf(ApiException.class).hasMessageContaining("접근할 수 없습니다");
        verify(service, never()).saveMetaAssets(any(), any(), any(), any(), any());
    }

    @Test
    void duplicateSelectionUsesServerVerifiedNameAndPageRelationshipOnce() {
        var legitimate = availableAsset();
        when(service.getMetaConnection(1L, 3L, 2L)).thenReturn(meta());
        when(client.discoverAssets("private-token")).thenReturn(List.of(legitimate));
        business.selectMetaAssets(1L, 3L, 2L, new MetaAssetSelectRequest(List.of(selection("ig_1"), selection("ig_1"))));
        verify(service).saveMetaAssets(1L, 3L, 2L, "private-token", List.of(legitimate));
    }

    @Test
    void upstreamDiscoveryFailureDoesNotPersistPartialSelection() {
        when(service.getMetaConnection(1L, 3L, 2L)).thenReturn(meta());
        when(client.discoverAssets("private-token")).thenThrow(new ApiException(ApiCode.SERVER_ERROR));
        assertThatThrownBy(() -> business.selectMetaAssets(1L, 3L, 2L,
                new MetaAssetSelectRequest(List.of(selection("ig_1")))))
                .isInstanceOf(ApiException.class);
        verify(service, never()).saveMetaAssets(any(), any(), any(), any(), any());
    }

    private MetaConnectionEntity meta() {
        return MetaConnectionEntity.builder().accessToken("private-token").build();
    }

    private MetaGraphClient.DiscoveredAsset availableAsset() {
        return new MetaGraphClient.DiscoveredAsset("ig_1", "verified-name", PlatformType.INSTAGRAM, AssetType.PROFILE, "page_1");
    }

    private MetaAssetSelectRequest.Selection selection(String id) {
        return new MetaAssetSelectRequest.Selection(id, PlatformType.INSTAGRAM, AssetType.PROFILE);
    }
}
