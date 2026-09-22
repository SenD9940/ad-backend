package com.orinan.api.domain.metaad;

import com.orinan.api.common.exception.ApiException;
import com.orinan.api.common.time.SeoulDateTimes;
import com.orinan.api.domain.metaad.service.MetaAdService;
import com.orinan.api.domain.metaad.service.MetaAdService.SavedAdAccount;
import com.orinan.api.domain.platformconnection.service.PlatformConnectionService;
import com.orinan.api.domain.user.exception.UserErrorCode;
import com.orinan.db.metaconnection.MetaConnectionEntity;
import com.orinan.db.metaconnection.MetaConnectionRepository;
import com.orinan.db.platformasset.PlatformAssetEntity;
import com.orinan.db.platformasset.PlatformAssetRepository;
import com.orinan.db.platformasset.enums.AssetType;
import com.orinan.db.platformasset.enums.PlatformType;
import com.orinan.db.platformconnection.PlatformConnectionEntity;
import com.orinan.db.platformconnection.PlatformConnectionRepository;
import com.orinan.db.platformconnection.enums.ProviderType;
import com.orinan.db.workspace.WorkspaceEntity;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class MetaAdServiceTest {

    private final PlatformConnectionService platformConnections = mock(PlatformConnectionService.class);
    private final PlatformConnectionRepository connections = mock(PlatformConnectionRepository.class);
    private final MetaConnectionRepository metaConnections = mock(MetaConnectionRepository.class);
    private final PlatformAssetRepository assets = mock(PlatformAssetRepository.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final MetaAdService service = new MetaAdService(
            platformConnections, connections, metaConnections, assets, entityManager);

    @Test
    void outsiderCannotReadAnySavedAccountOrToken() {
        doThrow(new ApiException(UserErrorCode.USER_PERMISSION_DENY))
                .when(platformConnections).requireMember(10L, 2L);

        assertThatThrownBy(() -> service.getAdAccount(10L, 30L, 2L)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.getAdAccounts(10L, 2L)).isInstanceOf(ApiException.class);

        verifyNoInteractions(connections, metaConnections, assets, entityManager);
    }

    @Test
    void acceptedMemberReadsSavedAccountWithSharedWorkspaceToken() {
        var asset = asset(30L, 20L, "act_123");
        stubSingle(asset, token(20L, "shared-token"));

        var account = service.getAdAccount(10L, 30L, 2L);

        assertThat(account).isEqualTo(new SavedAdAccount(30L, 20L, "act_123", "광고 계정", "shared-token"));
        assertThat(account.toString()).doesNotContain("shared-token").contains("REDACTED");
        verify(platformConnections).getMetaConnection(10L, 20L, 2L);
        verify(entityManager).refresh(asset);
    }

    @Test
    void unknownOrOtherWorkspaceAssetFailsBeforeCredentialAccess() {
        assertThatThrownBy(() -> service.getAdAccount(10L, 99L, 2L)).isInstanceOf(ApiException.class);
        verify(assets).findByIdAndWorkspaceId(99L, 10L);
        verify(platformConnections, never()).getMetaConnection(anyLong(), anyLong(), anyLong());
    }

    @Test
    void rejectsOrphansNonAdAssetsAndInvalidExternalIdsBeforeCredentialAccess() {
        var asset = asset(30L, 20L, "act_123");
        when(assets.findByIdAndWorkspaceId(30L, 10L)).thenReturn(Optional.of(asset));

        asset.setConnectionId(null);
        assertThatThrownBy(() -> service.getAdAccount(10L, 30L, 2L)).isInstanceOf(ApiException.class);
        asset.setConnectionId(20L);
        asset.setPlatformType(PlatformType.INSTAGRAM);
        assertThatThrownBy(() -> service.getAdAccount(10L, 30L, 2L)).isInstanceOf(ApiException.class);
        asset.setPlatformType(PlatformType.FACEBOOK);
        asset.setAssetType(AssetType.PROFILE);
        assertThatThrownBy(() -> service.getAdAccount(10L, 30L, 2L)).isInstanceOf(ApiException.class);
        asset.setAssetType(AssetType.AD_ACCOUNT);
        asset.setExternalId("act_123/insights?access_token=other");
        assertThatThrownBy(() -> service.getAdAccount(10L, 30L, 2L)).isInstanceOf(ApiException.class);

        verify(platformConnections, never()).getMetaConnection(anyLong(), anyLong(), anyLong());
    }

    @Test
    void rechecksAssetWorkspaceAfterRefreshingCachedEntity() {
        var asset = asset(30L, 20L, "act_123");
        when(assets.findByIdAndWorkspaceId(30L, 10L)).thenReturn(Optional.of(asset));
        doAnswer(invocation -> {
            asset.setWorkspaceId(99L);
            return null;
        }).when(entityManager).refresh(asset);

        assertThatThrownBy(() -> service.getAdAccount(10L, 30L, 2L)).isInstanceOf(ApiException.class);
        verify(platformConnections, never()).getMetaConnection(anyLong(), anyLong(), anyLong());
    }

    @Test
    void emptyTokenIsRejectedBeforeExternalRequest() {
        stubSingle(asset(30L, 20L, "act_123"), token(20L, " "));
        assertThatThrownBy(() -> service.getAdAccount(10L, 30L, 2L))
                .isInstanceOf(ApiException.class).hasMessageContaining("다시 연결");
    }

    @Test
    void deduplicatesAccountsUsingMostRecentlyUpdatedUsableConnection() {
        var older = connection(20L);
        var newer = connection(21L);
        older.setUpdatedAt(SeoulDateTimes.now().minusDays(1));
        newer.setUpdatedAt(SeoulDateTimes.now());
        when(connections.findAllByWorkspaceIdOrderByIdDesc(10L)).thenReturn(List.of(older, newer));
        stubCandidate(older, asset(30L, 20L, "act_123"), token(20L, "older-token"));
        stubCandidate(newer, asset(31L, 21L, "act_123"), token(21L, "newer-token"));

        var result = service.getAdAccounts(10L, 2L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).assetId()).isEqualTo(31L);
        assertThat(result.get(0).accessToken()).isEqualTo("newer-token");
    }

    @Test
    void fallsBackToUsableDuplicateWhenNewestTokenHasExpired() {
        var older = connection(20L);
        var newer = connection(21L);
        var expired = token(21L, "expired-token");
        expired.setExpiresAt(SeoulDateTimes.now().minusMinutes(1));
        when(connections.findAllByWorkspaceIdOrderByIdDesc(10L)).thenReturn(List.of(newer, older));
        stubCandidate(newer, asset(31L, 21L, "act_123"), expired);
        stubCandidate(older, asset(30L, 20L, "act_123"), token(20L, "valid-token"));

        assertThat(service.getAdAccounts(10L, 2L)).singleElement()
                .extracting(SavedAdAccount::accessToken).isEqualTo("valid-token");
        verify(platformConnections, never()).getMetaConnection(10L, 21L, 2L);
    }

    @Test
    void doesNotReturnPartialAccountTotalsWhenAnyAccountHasNoUsableCredential() {
        var first = connection(20L);
        var second = connection(21L);
        second.setRequiresReauth(true);
        when(connections.findAllByWorkspaceIdOrderByIdDesc(10L)).thenReturn(List.of(second, first));
        stubCandidate(first, asset(30L, 20L, "act_123"), token(20L, "valid-token"));
        when(assets.findAllByConnectionIdOrderByIdAsc(21L)).thenReturn(List.of(asset(31L, 21L, "act_456")));

        assertThatThrownBy(() -> service.getAdAccounts(10L, 2L))
                .isInstanceOf(ApiException.class).hasMessageContaining("다시 연결");
        verify(platformConnections, never()).getMetaConnection(10L, 21L, 2L);
    }

    @Test
    void listingExcludesOtherProvidersOrphansAndInconsistentWorkspaceAssets() {
        var meta = connection(20L);
        var naver = connection(21L);
        naver.setProviderType(ProviderType.NAVER);
        var otherWorkspace = connection(22L);
        otherWorkspace.setWorkspace(WorkspaceEntity.builder().id(99L).build());
        var orphan = asset(30L, null, "act_123");
        var foreignAsset = asset(31L, 20L, "act_456");
        foreignAsset.setWorkspaceId(99L);
        var instagram = asset(32L, 20L, "act_789");
        instagram.setPlatformType(PlatformType.INSTAGRAM);
        when(connections.findAllByWorkspaceIdOrderByIdDesc(10L)).thenReturn(List.of(naver, meta, otherWorkspace));
        when(assets.findAllByConnectionIdOrderByIdAsc(20L)).thenReturn(List.of(orphan, foreignAsset, instagram));

        assertThat(service.getAdAccounts(10L, 2L)).isEmpty();
        verify(assets, never()).findAllByConnectionIdOrderByIdAsc(21L);
        verify(assets, never()).findAllByConnectionIdOrderByIdAsc(22L);
        verifyNoInteractions(metaConnections);
    }

    @Test
    void missingCredentialPreventsSilentlyOmittingASavedAccount() {
        var connection = connection(20L);
        when(connections.findAllByWorkspaceIdOrderByIdDesc(10L)).thenReturn(List.of(connection));
        when(assets.findAllByConnectionIdOrderByIdAsc(20L)).thenReturn(List.of(asset(30L, 20L, "act_123")));

        assertThatThrownBy(() -> service.getAdAccounts(10L, 2L))
                .isInstanceOf(ApiException.class).hasMessageContaining("다시 연결");
    }

    @Test
    void databaseFailureIsNotSwallowedAsAnUnusableDuplicate() {
        var connection = connection(20L);
        when(connections.findAllByWorkspaceIdOrderByIdDesc(10L)).thenReturn(List.of(connection));
        when(assets.findAllByConnectionIdOrderByIdAsc(20L)).thenReturn(List.of(asset(30L, 20L, "act_123")));
        var failure = new DataAccessResourceFailureException("database unavailable");
        when(metaConnections.findById(20L)).thenThrow(failure);

        assertThatThrownBy(() -> service.getAdAccounts(10L, 2L)).isSameAs(failure);
    }

    @Test
    void revocationDuringRemoteLookupPreventsReturningResultsIncludingEmptyTotals() {
        doThrow(new ApiException(UserErrorCode.USER_PERMISSION_DENY))
                .when(platformConnections).requireMember(10L, 2L);

        assertThatThrownBy(() -> service.verifyUnchanged(10L, 2L, List.of()))
                .isInstanceOf(ApiException.class);
        verifyNoInteractions(assets, metaConnections);
    }

    @Test
    void tokenReplacementDuringRemoteLookupPreventsReturningStaleResults() {
        stubSingle(asset(30L, 20L, "act_123"), token(20L, "new-token"));

        assertThatThrownBy(() -> service.verifyUnchanged(10L, 2L,
                List.of(new SavedAdAccount(30L, 20L, "act_123", "광고 계정", "old-token"))))
                .isInstanceOf(ApiException.class).hasMessageContaining("연결 정보가 변경");
    }

    @Test
    void accountReplacementDuringRemoteLookupPreventsReturningStaleResults() {
        stubSingle(asset(30L, 20L, "act_456"), token(20L, "same-token"));

        assertThatThrownBy(() -> service.verifyUnchanged(10L, 2L,
                List.of(new SavedAdAccount(30L, 20L, "act_123", "광고 계정", "same-token"))))
                .isInstanceOf(ApiException.class).hasMessageContaining("연결 정보가 변경");
    }

    @Test
    void unchangedAccountPassesFinalAuthorizationEvenWhenLabelWasRenamed() {
        stubSingle(asset(30L, 20L, "act_123"), token(20L, "same-token"));

        service.verifyUnchanged(10L, 2L,
                List.of(new SavedAdAccount(30L, 20L, "act_123", "이전 이름", "same-token")));

        verify(platformConnections).getMetaConnection(10L, 20L, 2L);
    }

    private void stubSingle(PlatformAssetEntity asset, MetaConnectionEntity meta) {
        when(assets.findByIdAndWorkspaceId(asset.getId(), 10L)).thenReturn(Optional.of(asset));
        when(platformConnections.getMetaConnection(10L, asset.getConnectionId(), 2L)).thenReturn(meta);
    }

    private void stubCandidate(PlatformConnectionEntity connection, PlatformAssetEntity asset,
                               MetaConnectionEntity meta) {
        when(assets.findAllByConnectionIdOrderByIdAsc(connection.getId())).thenReturn(List.of(asset));
        when(metaConnections.findById(connection.getId())).thenReturn(Optional.of(meta));
        when(platformConnections.getMetaConnection(10L, connection.getId(), 2L)).thenReturn(meta);
    }

    private PlatformConnectionEntity connection(Long id) {
        return PlatformConnectionEntity.builder().id(id).providerType(ProviderType.META)
                .workspace(WorkspaceEntity.builder().id(10L).build()).requiresReauth(false).build();
    }

    private PlatformAssetEntity asset(Long id, Long connectionId, String externalId) {
        return PlatformAssetEntity.builder().id(id).workspaceId(10L).connectionId(connectionId)
                .platformType(PlatformType.FACEBOOK).assetType(AssetType.AD_ACCOUNT)
                .externalId(externalId).name("광고 계정").build();
    }

    private MetaConnectionEntity token(Long connectionId, String value) {
        return MetaConnectionEntity.builder().connectionId(connectionId).accessToken(value)
                .expiresAt(SeoulDateTimes.now().plusDays(30)).build();
    }
}
