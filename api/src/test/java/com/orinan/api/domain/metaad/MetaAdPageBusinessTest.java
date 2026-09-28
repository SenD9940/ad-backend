package com.orinan.api.domain.metaad;

import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.metaad.business.MetaAdPageBusiness;
import com.orinan.api.domain.metaad.controller.model.MetaAdPageSaveRequest;
import com.orinan.api.domain.metaad.service.MetaAdService;
import com.orinan.api.domain.metaad.service.MetaAdService.SavedAdAccount;
import com.orinan.api.domain.platformconnection.controller.model.PlatformAssetResponse;
import com.orinan.api.domain.platformconnection.meta.MetaGraphClient;
import com.orinan.api.domain.platformconnection.service.PlatformConnectionService;
import com.orinan.api.domain.user.exception.UserErrorCode;
import com.orinan.db.platformasset.enums.AssetType;
import com.orinan.db.platformasset.enums.PlatformType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MetaAdPageBusinessTest {

    private static final SavedAdAccount ACCOUNT = new SavedAdAccount(30L, 20L, "act_123", "광고 계정", "token");
    private static final MetaGraphClient.DiscoveredAsset PAGE = new MetaGraphClient.DiscoveredAsset(
            "9001", "Meta에서 조회한 이름", PlatformType.FACEBOOK, AssetType.PAGE, "9001");
    private final MetaAdService accounts = mock(MetaAdService.class);
    private final MetaGraphClient client = mock(MetaGraphClient.class);
    private final PlatformConnectionService connections = mock(PlatformConnectionService.class);
    private final MetaAdPageBusiness business = new MetaAdPageBusiness(accounts, client, connections);

    @Test
    void loadsOnlySelectedSavedAccountPagesAndRechecksAuthorizationBeforeReturning() {
        stubAccount();
        when(client.listPromotablePages("act_123", "token")).thenReturn(List.of(PAGE));

        assertThat(business.getPages(10L, 30L, 2L)).containsExactly(PAGE);

        var order = inOrder(accounts, client);
        order.verify(accounts).getAdAccountForManagement(10L, 30L, 2L);
        order.verify(client).listPromotablePages("act_123", "token");
        order.verify(accounts).verifyManagementUnchanged(10L, 2L, ACCOUNT);
        verifyNoInteractions(connections);
    }

    @Test
    void savesFreshVerifiedPageToTheAccountConnectionAndReturnsInternalIdsIncludingExistingAssets() {
        stubAccount();
        when(client.listPromotablePages("act_123", "token")).thenReturn(List.of(PAGE));
        var saved = List.of(
                new PlatformAssetResponse(30L, "act_123", "광고 계정", PlatformType.FACEBOOK, AssetType.AD_ACCOUNT, null),
                new PlatformAssetResponse(44L, "9001", PAGE.name(), PlatformType.FACEBOOK, AssetType.PAGE, "9001"));
        when(connections.saveMetaAssets(10L, 20L, 2L, "token", List.of(PAGE))).thenReturn(saved);

        assertThat(business.savePage(10L, 30L, 2L, new MetaAdPageSaveRequest("9001"))).isEqualTo(saved);

        var order = inOrder(accounts, client, connections);
        order.verify(accounts).getAdAccountForManagement(10L, 30L, 2L);
        order.verify(client).listPromotablePages("act_123", "token");
        order.verify(accounts).verifyManagementUnchanged(10L, 2L, ACCOUNT);
        order.verify(connections).saveMetaAssets(10L, 20L, 2L, "token", List.of(PAGE));
    }

    @Test
    void pageFromAnotherAccountOrARevokedPageCannotBeSaved() {
        stubAccount();
        when(client.listPromotablePages("act_123", "token")).thenReturn(List.of(PAGE), List.of());

        assertThatThrownBy(() -> business.savePage(10L, 30L, 2L, new MetaAdPageSaveRequest("9999")))
                .isInstanceOf(ApiException.class).hasMessageContaining("사용할 수 없는 페이지");
        assertThatThrownBy(() -> business.savePage(10L, 30L, 2L, new MetaAdPageSaveRequest("9001")))
                .isInstanceOf(ApiException.class).hasMessageContaining("사용할 수 없는 페이지");

        verify(client, times(2)).listPromotablePages("act_123", "token");
        verifyNoInteractions(connections);
    }

    @Test
    void rejectsUnauthorizedAccountsBeforeAnyExternalLookupOrSave() {
        var denied = new ApiException(UserErrorCode.USER_PERMISSION_DENY);
        when(accounts.getAdAccountForManagement(10L, 30L, 2L)).thenThrow(denied);

        assertThatThrownBy(() -> business.getPages(10L, 30L, 2L)).isSameAs(denied);
        assertThatThrownBy(() -> business.savePage(10L, 30L, 2L, new MetaAdPageSaveRequest("9001"))).isSameAs(denied);

        verifyNoInteractions(client, connections);
    }

    @Test
    void permissionsOrConnectionChangedDuringLookupPreventReturningOrSavingStalePages() {
        stubAccount();
        when(client.listPromotablePages("act_123", "token")).thenReturn(List.of(PAGE));
        var changed = new ApiException(ApiCode.BAD_REQUEST, "연결 정보가 변경되었습니다.");
        doThrow(changed).when(accounts).verifyManagementUnchanged(10L, 2L, ACCOUNT);

        assertThatThrownBy(() -> business.getPages(10L, 30L, 2L)).isSameAs(changed);
        assertThatThrownBy(() -> business.savePage(10L, 30L, 2L, new MetaAdPageSaveRequest("9001"))).isSameAs(changed);

        verifyNoInteractions(connections);
    }

    @Test
    void graphFailureDoesNotFallbackToUserPagesOrSavedAssets() {
        stubAccount();
        var failure = new ApiException(ApiCode.BAD_REQUEST, "Meta 페이지 권한을 확인해 주세요.");
        when(client.listPromotablePages("act_123", "token")).thenThrow(failure);

        assertThatThrownBy(() -> business.getPages(10L, 30L, 2L)).isSameAs(failure);
        assertThatThrownBy(() -> business.savePage(10L, 30L, 2L, new MetaAdPageSaveRequest("9001"))).isSameAs(failure);

        verify(client, never()).discoverAssets(anyString());
        verifyNoInteractions(connections);
    }

    private void stubAccount() {
        when(accounts.getAdAccountForManagement(10L, 30L, 2L)).thenReturn(ACCOUNT);
    }
}
