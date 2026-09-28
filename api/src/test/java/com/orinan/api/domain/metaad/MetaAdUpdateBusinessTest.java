package com.orinan.api.domain.metaad;

import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.metaad.business.MetaAdBusiness;
import com.orinan.api.domain.metaad.controller.model.MetaAdBudgetUpdateRequest;
import com.orinan.api.domain.metaad.controller.model.MetaAdUpdateRequest;
import com.orinan.api.domain.metaad.service.MetaAdImageService;
import com.orinan.api.domain.metaad.service.MetaAdService;
import com.orinan.api.domain.metaad.service.MetaAdService.SavedAdAccount;
import com.orinan.api.domain.platformconnection.meta.MetaGraphClient;
import com.orinan.api.domain.platformconnection.meta.MetaGraphClient.AdSetForUpdate;
import com.orinan.api.domain.platformconnection.meta.MetaGraphClient.CampaignForUpdate;
import com.orinan.api.domain.platformconnection.meta.MetaGraphClient.UpdatedAdObject;
import com.orinan.api.domain.user.exception.UserErrorCode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.orinan.api.domain.metaad.controller.model.MetaAdUpdateRequest.Status.ACTIVE;
import static com.orinan.api.domain.metaad.controller.model.MetaAdUpdateRequest.Status.PAUSED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class MetaAdUpdateBusinessTest {

    private static final SavedAdAccount ACCOUNT = new SavedAdAccount(30L, 20L, "act_123", "광고 계정", "shared-secret-token");
    private final MetaAdService service = mock(MetaAdService.class);
    private final MetaGraphClient client = mock(MetaGraphClient.class);
    private final MetaAdImageService images = mock(MetaAdImageService.class);
    private final MetaAdBusiness business = new MetaAdBusiness(service, client, images);

    @Test
    void changesAdStatusAfterOwnershipAndManagementRecheckWithoutChangingParents() {
        authorize();
        var result = new UpdatedAdObject("1004", true);
        when(client.updateAd("act_123", "shared-secret-token", "1004", null, "ACTIVE")).thenReturn(result);

        assertThat(business.updateAd(10L, 30L, 2L, "1004", new MetaAdUpdateRequest(null, ACTIVE))).isEqualTo(result);

        var order = inOrder(service, client);
        order.verify(service).getAdAccountForManagement(10L, 30L, 2L);
        order.verify(client).verifyAdForUpdate("act_123", "shared-secret-token", "1004");
        order.verify(service).verifyManagementUnchanged(10L, 2L, ACCOUNT);
        order.verify(client).updateAd("act_123", "shared-secret-token", "1004", null, "ACTIVE");
        verifyNoMoreInteractions(service, client);
        verifyNoInteractions(images);
    }

    @Test
    void pausesAdAndChangesNameInOneRequest() {
        authorize();

        business.updateAd(10L, 30L, 2L, "1004", new MetaAdUpdateRequest("수정된 광고", PAUSED));

        verify(client).updateAd("act_123", "shared-secret-token", "1004", "수정된 광고", "PAUSED");
    }

    @Test
    void changesOnlyAdNameWithoutSendingStatus() {
        authorize();

        business.updateAd(10L, 30L, 2L, "1004", new MetaAdUpdateRequest("수정된 광고", null));

        verify(client).updateAd("act_123", "shared-secret-token", "1004", "수정된 광고", null);
    }

    @Test
    void adjustsExistingAdSetDailyBudgetAfterCheckingParentBudgetAndPermissions() {
        authorize();
        when(client.getAdSetForUpdate("act_123", "shared-secret-token", "1002"))
                .thenReturn(new AdSetForUpdate("1002", "1001", 10000L, 0L));
        when(client.getCampaignForUpdate("act_123", "shared-secret-token", "1001"))
                .thenReturn(new CampaignForUpdate("1001", 0L, 0L));
        var result = new UpdatedAdObject("1002", true);
        when(client.updateAdSet("act_123", "shared-secret-token", "1002", null, null, 20000L)).thenReturn(result);

        assertThat(business.updateAdSet(10L, 30L, 2L, "1002", new MetaAdBudgetUpdateRequest(null, null, 20000L)))
                .isEqualTo(result);

        var order = inOrder(service, client);
        order.verify(service).getAdAccountForManagement(10L, 30L, 2L);
        order.verify(client).getAdSetForUpdate("act_123", "shared-secret-token", "1002");
        order.verify(client).getCampaignForUpdate("act_123", "shared-secret-token", "1001");
        order.verify(service).verifyManagementUnchanged(10L, 2L, ACCOUNT);
        order.verify(client).updateAdSet("act_123", "shared-secret-token", "1002", null, null, 20000L);
        verifyNoMoreInteractions(service, client);
    }

    @Test
    void adjustsExistingCampaignDailyBudget() {
        authorize();
        when(client.getCampaignForUpdate("act_123", "shared-secret-token", "1001"))
                .thenReturn(new CampaignForUpdate("1001", 10000L, 0L));
        var result = new UpdatedAdObject("1001", true);
        when(client.updateCampaign("act_123", "shared-secret-token", "1001", "새 캠페인", "PAUSED", 20000L))
                .thenReturn(result);

        assertThat(business.updateCampaign(10L, 30L, 2L, "1001", new MetaAdBudgetUpdateRequest("새 캠페인", PAUSED, 20000L)))
                .isEqualTo(result);

        var order = inOrder(service, client);
        order.verify(service).getAdAccountForManagement(10L, 30L, 2L);
        order.verify(client).getCampaignForUpdate("act_123", "shared-secret-token", "1001");
        order.verify(service).verifyManagementUnchanged(10L, 2L, ACCOUNT);
        order.verify(client).updateCampaign("act_123", "shared-secret-token", "1001", "새 캠페인", "PAUSED", 20000L);
        verifyNoMoreInteractions(service, client);
    }

    @Test
    void statusAndNameOnlyUpdatesDoNotRequireDailyBudgetsOrChangeBudgetMode() {
        authorize();
        when(client.getCampaignForUpdate("act_123", "shared-secret-token", "1001"))
                .thenReturn(new CampaignForUpdate("1001", 0L, 100000L));
        when(client.getAdSetForUpdate("act_123", "shared-secret-token", "1002"))
                .thenReturn(new AdSetForUpdate("1002", "1001", 0L, 100000L));

        business.updateCampaign(10L, 30L, 2L, "1001", new MetaAdBudgetUpdateRequest(null, ACTIVE, null));
        business.updateAdSet(10L, 30L, 2L, "1002", new MetaAdBudgetUpdateRequest("새 광고 세트", PAUSED, null));

        verify(client).updateCampaign("act_123", "shared-secret-token", "1001", null, "ACTIVE", null);
        verify(client).updateAdSet("act_123", "shared-secret-token", "1002", "새 광고 세트", "PAUSED", null);
        // Only the campaign edit itself reads the campaign; status edits on an ad set do not inspect parent budgets.
        verify(client, times(1)).getCampaignForUpdate(anyString(), anyString(), anyString());
    }

    @Test
    void rejectsAdSetBudgetWhenCampaignOwnsDailyOrLifetimeBudget() {
        authorize();
        when(client.getAdSetForUpdate("act_123", "shared-secret-token", "1002"))
                .thenReturn(new AdSetForUpdate("1002", "1001", 10000L, 0L));
        for (var campaign : List.of(new CampaignForUpdate("1001", 10000L, 0L),
                new CampaignForUpdate("1001", 0L, 100000L))) {
            when(client.getCampaignForUpdate("act_123", "shared-secret-token", "1001")).thenReturn(campaign);

            assertBadRequest(() -> business.updateAdSet(10L, 30L, 2L, "1002", new MetaAdBudgetUpdateRequest(null, null, 20000L)));
        }

        verify(client, never()).updateAdSet(anyString(), anyString(), anyString(), any(), any(), any());
        verify(service, never()).verifyManagementUnchanged(anyLong(), anyLong(), any());
    }

    @Test
    void rejectsImplicitCampaignBudgetModeSwitch() {
        authorize();
        for (var campaign : List.of(new CampaignForUpdate("1001", 0L, 0L),
                new CampaignForUpdate("1001", 0L, 100000L), new CampaignForUpdate("1001", 10000L, 100000L))) {
            when(client.getCampaignForUpdate("act_123", "shared-secret-token", "1001")).thenReturn(campaign);

            assertBadRequest(() -> business.updateCampaign(10L, 30L, 2L, "1001", new MetaAdBudgetUpdateRequest(null, null, 20000L)));
        }

        verify(client, never()).updateCampaign(anyString(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void rejectsImplicitAdSetBudgetModeSwitch() {
        authorize();
        when(client.getCampaignForUpdate("act_123", "shared-secret-token", "1001"))
                .thenReturn(new CampaignForUpdate("1001", 0L, 0L));
        for (var adSet : List.of(new AdSetForUpdate("1002", "1001", 0L, 0L),
                new AdSetForUpdate("1002", "1001", 0L, 100000L), new AdSetForUpdate("1002", "1001", 10000L, 100000L))) {
            when(client.getAdSetForUpdate("act_123", "shared-secret-token", "1002")).thenReturn(adSet);

            assertBadRequest(() -> business.updateAdSet(10L, 30L, 2L, "1002", new MetaAdBudgetUpdateRequest(null, null, 20000L)));
        }

        verify(client, never()).updateAdSet(anyString(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void deniedWorkspaceMembershipOrManagementScopeStopsBeforeMetaAccessForEveryRoute() {
        var denied = new ApiException(UserErrorCode.USER_PERMISSION_DENY);
        when(service.getAdAccountForManagement(10L, 30L, 2L)).thenThrow(denied);

        assertThatThrownBy(() -> business.updateAd(10L, 30L, 2L, "1004", new MetaAdUpdateRequest(null, PAUSED))).isSameAs(denied);
        assertThatThrownBy(() -> business.updateAdSet(10L, 30L, 2L, "1002", new MetaAdBudgetUpdateRequest(null, PAUSED, null))).isSameAs(denied);
        assertThatThrownBy(() -> business.updateCampaign(10L, 30L, 2L, "1001", new MetaAdBudgetUpdateRequest(null, PAUSED, null))).isSameAs(denied);

        verifyNoInteractions(client, images);
    }

    @Test
    void crossAccountObjectRejectionStopsEveryUpdateBeforeWrite() {
        authorize();
        var denied = new ApiException(UserErrorCode.USER_PERMISSION_DENY);
        doThrow(denied).when(client).verifyAdForUpdate("act_123", "shared-secret-token", "1004");
        when(client.getAdSetForUpdate("act_123", "shared-secret-token", "1002")).thenThrow(denied);
        when(client.getCampaignForUpdate("act_123", "shared-secret-token", "1001")).thenThrow(denied);

        assertThatThrownBy(() -> business.updateAd(10L, 30L, 2L, "1004", new MetaAdUpdateRequest(null, ACTIVE))).isSameAs(denied);
        assertThatThrownBy(() -> business.updateAdSet(10L, 30L, 2L, "1002", new MetaAdBudgetUpdateRequest(null, ACTIVE, null))).isSameAs(denied);
        assertThatThrownBy(() -> business.updateCampaign(10L, 30L, 2L, "1001", new MetaAdBudgetUpdateRequest(null, ACTIVE, null))).isSameAs(denied);

        verify(client, never()).updateAd(anyString(), anyString(), anyString(), any(), any());
        verify(client, never()).updateAdSet(anyString(), anyString(), anyString(), any(), any(), any());
        verify(client, never()).updateCampaign(anyString(), anyString(), anyString(), any(), any(), any());
        verify(service, never()).verifyManagementUnchanged(anyLong(), anyLong(), any());
    }

    @Test
    void revokedManagementAfterOwnershipReadStopsEveryUpdateBeforeWrite() {
        authorize();
        when(client.getCampaignForUpdate("act_123", "shared-secret-token", "1001"))
                .thenReturn(new CampaignForUpdate("1001", 0L, 0L));
        when(client.getAdSetForUpdate("act_123", "shared-secret-token", "1002"))
                .thenReturn(new AdSetForUpdate("1002", "1001", 0L, 0L));
        var denied = new ApiException(UserErrorCode.USER_PERMISSION_DENY);
        doThrow(denied).when(service).verifyManagementUnchanged(10L, 2L, ACCOUNT);

        assertThatThrownBy(() -> business.updateAd(10L, 30L, 2L, "1004", new MetaAdUpdateRequest(null, PAUSED))).isSameAs(denied);
        assertThatThrownBy(() -> business.updateAdSet(10L, 30L, 2L, "1002", new MetaAdBudgetUpdateRequest(null, PAUSED, null))).isSameAs(denied);
        assertThatThrownBy(() -> business.updateCampaign(10L, 30L, 2L, "1001", new MetaAdBudgetUpdateRequest(null, PAUSED, null))).isSameAs(denied);

        verify(client, never()).updateAd(anyString(), anyString(), anyString(), any(), any());
        verify(client, never()).updateAdSet(anyString(), anyString(), anyString(), any(), any(), any());
        verify(client, never()).updateCampaign(anyString(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void invalidObjectIdsFailBeforeDatabaseOrMetaAccess() {
        for (String id : List.of("", " ", "act_123", "123/ads", "123?access_token=secret", "123%2Fads", "1".repeat(33))) {
            assertBadRequest(() -> business.updateAd(10L, 30L, 2L, id, new MetaAdUpdateRequest(null, ACTIVE)));
            assertBadRequest(() -> business.updateAdSet(10L, 30L, 2L, id, new MetaAdBudgetUpdateRequest(null, ACTIVE, null)));
            assertBadRequest(() -> business.updateCampaign(10L, 30L, 2L, id, new MetaAdBudgetUpdateRequest(null, ACTIVE, null)));
        }

        verifyNoInteractions(service, client, images);
    }

    @Test
    void emptyBlankOrInvalidBudgetPatchesFailBeforeDatabaseOrMetaAccess() {
        for (var request : List.of(new MetaAdUpdateRequest(null, null), new MetaAdUpdateRequest(" ", ACTIVE),
                new MetaAdUpdateRequest("x".repeat(256), null))) {
            assertBadRequest(() -> business.updateAd(10L, 30L, 2L, "1004", request));
        }
        for (var request : List.of(new MetaAdBudgetUpdateRequest(null, null, null),
                new MetaAdBudgetUpdateRequest(" ", ACTIVE, null), new MetaAdBudgetUpdateRequest("x".repeat(256), null, null),
                new MetaAdBudgetUpdateRequest(null, null, 0L), new MetaAdBudgetUpdateRequest(null, null, -1L))) {
            assertBadRequest(() -> business.updateAdSet(10L, 30L, 2L, "1002", request));
            assertBadRequest(() -> business.updateCampaign(10L, 30L, 2L, "1001", request));
        }

        verifyNoInteractions(service, client, images);
    }

    @Test
    void remoteUpdateFailureIsNotRetriedOrFollowedByOtherWrites() {
        authorize();
        var failure = new ApiException(ApiCode.SERVER_ERROR, "Meta 수정 결과를 확인해 주세요.");
        when(client.updateAd("act_123", "shared-secret-token", "1004", null, "ACTIVE")).thenThrow(failure);

        assertThatThrownBy(() -> business.updateAd(10L, 30L, 2L, "1004", new MetaAdUpdateRequest(null, ACTIVE)))
                .isSameAs(failure);

        verify(client).verifyAdForUpdate("act_123", "shared-secret-token", "1004");
        verify(client, times(1)).updateAd("act_123", "shared-secret-token", "1004", null, "ACTIVE");
        verifyNoMoreInteractions(client);
    }

    private void authorize() {
        when(service.getAdAccountForManagement(10L, 30L, 2L)).thenReturn(ACCOUNT);
    }

    private void assertBadRequest(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,
                exception -> assertThat(exception.getCodeIfs()).isEqualTo(ApiCode.BAD_REQUEST));
    }
}
