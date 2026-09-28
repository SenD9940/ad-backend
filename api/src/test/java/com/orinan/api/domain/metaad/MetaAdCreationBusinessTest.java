package com.orinan.api.domain.metaad;

import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.metaad.business.MetaAdBusiness;
import com.orinan.api.domain.metaad.controller.model.MetaAdCreateRequest;
import com.orinan.api.domain.metaad.controller.model.MetaAdCreateResponse;
import com.orinan.api.domain.metaad.controller.model.MetaAdImageUploadResponse;
import com.orinan.api.domain.metaad.controller.model.MetaCampaignCreateRequest;
import com.orinan.api.domain.metaad.exception.MetaAdCreationException;
import com.orinan.api.domain.metaad.service.MetaAdImageService;
import com.orinan.api.domain.metaad.service.MetaAdService;
import com.orinan.api.domain.metaad.service.MetaAdService.AdIdentity;
import com.orinan.api.domain.metaad.service.MetaAdService.SavedAdAccount;
import com.orinan.api.domain.platformconnection.meta.MetaGraphClient;
import com.orinan.api.domain.platformconnection.meta.MetaGraphClient.AdSetSpec;
import com.orinan.api.domain.platformconnection.meta.MetaGraphClient.CreativeSpec;
import com.orinan.api.domain.platformconnection.meta.MetaGraphClient.CreatedAd;
import com.orinan.api.domain.platformconnection.meta.MetaGraphClient.CreatedAdSet;
import com.orinan.api.domain.platformconnection.meta.MetaGraphClient.CreatedCampaign;
import com.orinan.api.domain.platformconnection.meta.MetaGraphClient.CreatedCreative;
import com.orinan.api.domain.user.exception.UserErrorCode;
import com.orinan.db.platformasset.enums.AssetType;
import com.orinan.db.platformasset.enums.PlatformType;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockMultipartFile;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class MetaAdCreationBusinessTest {

    private static final SavedAdAccount ACCOUNT = new SavedAdAccount(30L, 20L, "act_123", "광고 계정", "shared-secret-token");
    private static final AdIdentity IDENTITY = new AdIdentity("9001", "9002");
    private static final String IMAGE_KEY = "auto-threads/workspaces/10/meta/ad-accounts/30/images/9cf3db78-b05a-4e28-af20-c43946892038.jpg";
    private final MetaAdService service = mock(MetaAdService.class);
    private final MetaGraphClient client = mock(MetaGraphClient.class);
    private final MetaAdImageService images = mock(MetaAdImageService.class);
    private final MetaAdBusiness business = new MetaAdBusiness(service, client, images);

    @Test
    void createsCampaignAdSetCreativeAndAdInOrderUsingAuthorizedSavedIdentities() {
        var request = trafficRequest();
        stubAccountAndIdentity(request);
        stubCreatedObjects();

        var response = business.createAd(10L, 30L, 2L, request);

        assertThat(response.assetId()).isEqualTo(30L);
        assertThat(response.adAccountId()).isEqualTo("act_123");
        assertThat(response.campaignId()).isEqualTo("1001");
        assertThat(response.adSetId()).isEqualTo("1002");
        assertThat(response.creativeId()).isEqualTo("1003");
        assertThat(response.adId()).isEqualTo("1004");
        assertThat(response.status()).isEqualTo(MetaAdCreateResponse.Status.CREATED);
        assertThat(response.failedStep()).isNull();

        var order = inOrder(service, client);
        order.verify(service).getAdAccountForManagement(10L, 30L, 2L);
        order.verify(service).getAdIdentity(10L, 2L, ACCOUNT, 40L, 41L);
        order.verify(client).listPromotablePages("act_123", "shared-secret-token");
        order.verify(service).verifyManagementUnchanged(10L, 2L, ACCOUNT);
        order.verify(client).createCampaign("act_123", "shared-secret-token", "유입 캠페인", "OUTCOME_TRAFFIC", List.of(), List.of());
        order.verify(service).getAdIdentity(10L, 2L, ACCOUNT, 40L, 41L);
        order.verify(client).createAdSet("act_123", "shared-secret-token", "1001",
                new AdSetSpec("한국 광고 세트", "OUTCOME_TRAFFIC", 10000L, List.of("KR"), 20, 50, null, true));
        order.verify(service).getAdIdentity(10L, 2L, ACCOUNT, 40L, 41L);
        order.verify(client).createImageCreative("act_123", "shared-secret-token",
                new CreativeSpec("상품 광고", "9001", "9002", "https://shop.example.com/product/1",
                        "https://cdn.example.com/product.jpg", "상품을 확인하세요", "신상품", "상품 설명", "LEARN_MORE"));
        order.verify(service).getAdIdentity(10L, 2L, ACCOUNT, 40L, 41L);
        order.verify(client).createAd("act_123", "shared-secret-token", "상품 광고", "1002", "1003");
        verifyNoMoreInteractions(service, client);
        verifyNoInteractions(images);
    }

    @Test
    void salesCampaignUsesPixelAndFacebookOnlyIdentityWhenInstagramWasNotSelected() {
        var request = new MetaAdCreateRequest(
                new MetaCampaignCreateRequest("판매 캠페인", MetaCampaignCreateRequest.Objective.OUTCOME_SALES, List.of(), null),
                new MetaAdCreateRequest.AdSet("판매 광고 세트", 20000L, List.of("KR"), 18, 65, "8888"),
                new MetaAdCreateRequest.Ad("상품 광고", 40L, null, "https://cdn.example.com/product.jpg",
                        "https://shop.example.com/product/1", "상품을 확인하세요", "신상품", null, MetaAdCreateRequest.CallToAction.SHOP_NOW, null));
        when(service.getAdAccountForManagement(10L, 30L, 2L)).thenReturn(ACCOUNT);
        when(service.getAdIdentity(10L, 2L, ACCOUNT, 40L, null)).thenReturn(new AdIdentity("9001", null));
        stubCreatedObjects();

        assertThat(business.createAd(10L, 30L, 2L, request).adId()).isEqualTo("1004");

        verify(client).createAdSet("act_123", "shared-secret-token", "1001",
                new AdSetSpec("판매 광고 세트", "OUTCOME_SALES", 20000L, List.of("KR"), 18, 65, "8888", false));
        verify(client).createImageCreative("act_123", "shared-secret-token",
                new CreativeSpec("상품 광고", "9001", null, "https://shop.example.com/product/1",
                        "https://cdn.example.com/product.jpg", "상품을 확인하세요", "신상품", null, "SHOP_NOW"));
    }

    @Test
    void invalidObjectivePixelOrAgeRangeFailsBeforeCreatingAnything() {
        var valid = trafficRequest();
        var invalid = List.of(
                new MetaAdCreateRequest(new MetaCampaignCreateRequest("인지도", MetaCampaignCreateRequest.Objective.OUTCOME_AWARENESS,
                        List.of(), null), valid.adSet(), valid.ad()),
                new MetaAdCreateRequest(new MetaCampaignCreateRequest("판매", MetaCampaignCreateRequest.Objective.OUTCOME_SALES,
                        List.of(), null), valid.adSet(), valid.ad()),
                new MetaAdCreateRequest(valid.campaign(), new MetaAdCreateRequest.AdSet("광고 세트", 10000L, List.of("KR"), 60, 20, null), valid.ad()));

        for (var request : invalid) {
            assertThatThrownBy(() -> business.createAd(10L, 30L, 2L, request))
                    .isInstanceOfSatisfying(ApiException.class, exception ->
                            assertThat(exception.getCodeIfs()).isEqualTo(ApiCode.BAD_REQUEST));
        }

        verifyNoInteractions(client);
    }

    @Test
    void adSetRejectionRetainsCreatedCampaignAndStopsWithoutRetriesOrLaterWrites() {
        var request = trafficRequest();
        stubAccountAndIdentity(request);
        stubCreatedObjects();
        when(client.createAdSet(anyString(), anyString(), anyString(), any(AdSetSpec.class)))
                .thenThrow(new MetaGraphClient.CreationException(ApiCode.BAD_REQUEST, "Meta 광고 세트 생성 실패", false));

        assertThatThrownBy(() -> business.createAd(10L, 30L, 2L, request))
                .isInstanceOfSatisfying(MetaAdCreationException.class, exception -> {
                    assertThat(exception.getResult().campaignId()).isEqualTo("1001");
                    assertThat(exception.getResult().adSetId()).isNull();
                    assertThat(exception.getResult().creativeId()).isNull();
                    assertThat(exception.getResult().adId()).isNull();
                    assertThat(exception.getResult().status()).isEqualTo(MetaAdCreateResponse.Status.FAILED);
                    assertThat(exception.getResult().failedStep()).isEqualTo(MetaAdCreateResponse.Step.AD_SET);
                });

        verify(client).createCampaign(anyString(), anyString(), anyString(), anyString(), anyList(), anyList());
        verify(client).createAdSet(anyString(), anyString(), anyString(), any(AdSetSpec.class));
        verify(client, never()).createImageCreative(anyString(), anyString(), any(CreativeSpec.class));
        verify(client, never()).createAd(anyString(), anyString(), anyString(), anyString(), anyString());
        verify(client).listPromotablePages("act_123", "shared-secret-token");
        verifyNoMoreInteractions(client);
    }

    @Test
    void unknownAdCreationOutcomeRetainsEveryConfirmedIdAndIsNotRetried() {
        var request = trafficRequest();
        stubAccountAndIdentity(request);
        stubCreatedObjects();
        when(client.createAd(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new MetaGraphClient.CreationException(ApiCode.SERVER_ERROR, "생성 여부 확인이 필요합니다.", true));

        assertThatThrownBy(() -> business.createAd(10L, 30L, 2L, request))
                .isInstanceOfSatisfying(MetaAdCreationException.class, exception -> {
                    assertThat(exception.getResult().campaignId()).isEqualTo("1001");
                    assertThat(exception.getResult().adSetId()).isEqualTo("1002");
                    assertThat(exception.getResult().creativeId()).isEqualTo("1003");
                    assertThat(exception.getResult().adId()).isNull();
                    assertThat(exception.getResult().status()).isEqualTo(MetaAdCreateResponse.Status.UNKNOWN);
                    assertThat(exception.getResult().failedStep()).isEqualTo(MetaAdCreateResponse.Step.AD);
                    assertThat(exception.getResult().message()).contains("확인");
                });

        verify(client).createAd("act_123", "shared-secret-token", "상품 광고", "1002", "1003");
        verify(client, never()).listCampaigns(anyString(), anyString());
        verify(service, times(4)).getAdIdentity(10L, 2L, ACCOUNT, 40L, 41L);
    }

    @Test
    void permissionRevokedAfterCampaignCreationStopsAndPreservesItsId() {
        var request = trafficRequest();
        stubAccountAndIdentity(request);
        stubCreatedObjects();
        when(service.getAdIdentity(10L, 2L, ACCOUNT, 40L, 41L)).thenReturn(IDENTITY)
                .thenThrow(new ApiException(UserErrorCode.USER_PERMISSION_DENY));

        assertThatThrownBy(() -> business.createAd(10L, 30L, 2L, request))
                .isInstanceOfSatisfying(MetaAdCreationException.class, exception -> {
                    assertThat(exception.getCodeIfs()).isEqualTo(UserErrorCode.USER_PERMISSION_DENY);
                    assertThat(exception.getResult().campaignId()).isEqualTo("1001");
                    assertThat(exception.getResult().status()).isEqualTo(MetaAdCreateResponse.Status.FAILED);
                    assertThat(exception.getResult().failedStep()).isEqualTo(MetaAdCreateResponse.Step.AD_SET);
                });

        verify(client).createCampaign(anyString(), anyString(), anyString(), anyString(), anyList(), anyList());
        verify(client).listPromotablePages("act_123", "shared-secret-token");
        verifyNoMoreInteractions(client);
    }

    @Test
    void databaseFailureAfterCampaignCreationPreservesItsIdWithoutExposingTheCause() {
        var request = trafficRequest();
        stubAccountAndIdentity(request);
        stubCreatedObjects();
        when(service.getAdIdentity(10L, 2L, ACCOUNT, 40L, 41L)).thenReturn(IDENTITY)
                .thenThrow(new DataAccessResourceFailureException("database shared-secret-token credential detail"));

        assertThatThrownBy(() -> business.createAd(10L, 30L, 2L, request))
                .isInstanceOfSatisfying(MetaAdCreationException.class, exception -> {
                    assertThat(exception.getCodeIfs()).isEqualTo(ApiCode.SERVER_ERROR);
                    assertThat(exception.getResult().campaignId()).isEqualTo("1001");
                    assertThat(exception.getResult().adSetId()).isNull();
                    assertThat(exception.getResult().status()).isEqualTo(MetaAdCreateResponse.Status.UNKNOWN);
                    assertThat(exception.getResult().failedStep()).isEqualTo(MetaAdCreateResponse.Step.AD_SET);
                    assertThat(exception.getResult().message()).doesNotContain("shared-secret-token", "credential detail");
                    assertThat(exception.getMessage()).doesNotContain("shared-secret-token", "credential detail");
                    assertThat(exception.getCause()).isNull();
                });

        verify(client).createCampaign(anyString(), anyString(), anyString(), anyString(), anyList(), anyList());
        verify(client).listPromotablePages("act_123", "shared-secret-token");
        verifyNoMoreInteractions(client);
    }

    @Test
    void changedSavedPageCannotBeUsedForSubsequentSteps() {
        var request = trafficRequest();
        stubAccountAndIdentity(request);
        stubCreatedObjects();
        when(service.getAdIdentity(10L, 2L, ACCOUNT, 40L, 41L)).thenReturn(IDENTITY, new AdIdentity("different-page", "9002"));

        assertThatThrownBy(() -> business.createAd(10L, 30L, 2L, request))
                .isInstanceOfSatisfying(MetaAdCreationException.class, exception -> {
                    assertThat(exception.getResult().campaignId()).isEqualTo("1001");
                    assertThat(exception.getResult().failedStep()).isEqualTo(MetaAdCreateResponse.Step.AD_SET);
                    assertThat(exception.getResult().status()).isEqualTo(MetaAdCreateResponse.Status.FAILED);
                });

        verify(client).createCampaign(anyString(), anyString(), anyString(), anyString(), anyList(), anyList());
        verify(client).listPromotablePages("act_123", "shared-secret-token");
        verifyNoMoreInteractions(client);
    }

    @Test
    void invalidSavedIdentityFailsBeforeAnyCampaignCanBeCreated() {
        var request = trafficRequest();
        when(service.getAdAccountForManagement(10L, 30L, 2L)).thenReturn(ACCOUNT);
        var failure = new ApiException(ApiCode.BAD_REQUEST, "저장된 페이지를 선택해 주세요.");
        when(service.getAdIdentity(10L, 2L, ACCOUNT, 40L, 41L)).thenThrow(failure);

        assertThatThrownBy(() -> business.createAd(10L, 30L, 2L, request)).isSameAs(failure);

        verifyNoInteractions(client);
    }

    @Test
    void savedPageThatIsNotPromotableBySelectedAccountFailsBeforeFirstWrite() {
        var request = trafficRequest();
        stubAccountAndIdentity(request);
        when(client.listPromotablePages("act_123", "shared-secret-token"))
                .thenReturn(List.of(new MetaGraphClient.DiscoveredAsset("9999", "다른 페이지",
                        PlatformType.FACEBOOK, AssetType.PAGE, "9999")));

        assertThatThrownBy(() -> business.createAd(10L, 30L, 2L, request))
                .isInstanceOf(ApiException.class).isNotInstanceOf(MetaAdCreationException.class)
                .hasMessageContaining("사용할 수 없는 페이지");

        verify(client).listPromotablePages("act_123", "shared-secret-token");
        verifyNoMoreInteractions(client);
    }

    @Test
    void accountChangedWhileCheckingPromotablePagesFailsBeforeFirstWrite() {
        var request = trafficRequest();
        stubAccountAndIdentity(request);
        stubCreatedObjects();
        var changed = new ApiException(ApiCode.BAD_REQUEST, "연결 정보가 변경되었습니다.");
        doThrow(changed).when(service).verifyManagementUnchanged(10L, 2L, ACCOUNT);

        assertThatThrownBy(() -> business.createAd(10L, 30L, 2L, request)).isSameAs(changed);

        verify(client).listPromotablePages("act_123", "shared-secret-token");
        verifyNoMoreInteractions(client);
    }

    @Test
    void promotablePageLookupFailureFailsBeforeFirstWriteWithoutPartialCreationResponse() {
        var request = trafficRequest();
        stubAccountAndIdentity(request);
        var failure = new ApiException(ApiCode.SERVER_ERROR, "Meta 페이지 조회에 실패했습니다.");
        when(client.listPromotablePages("act_123", "shared-secret-token")).thenThrow(failure);

        assertThatThrownBy(() -> business.createAd(10L, 30L, 2L, request)).isSameAs(failure);

        verify(client).listPromotablePages("act_123", "shared-secret-token");
        verifyNoMoreInteractions(client);
    }

    @Test
    void invalidPublicHttpsImageOrDestinationUrlsFailBeforeCreatingAnything() {
        var valid = trafficRequest();
        for (String url : List.of("http://cdn.example.com/p.jpg", "https://user:password@cdn.example.com/p.jpg",
                "https://cdn.example.com/p.jpg#fragment", "https://localhost/p.jpg", "https://127.0.0.1/p.jpg")) {
            var invalidImage = new MetaAdCreateRequest.Ad("광고", 40L, 41L, url, valid.ad().linkUrl(),
                    "문구", "제목", null, MetaAdCreateRequest.CallToAction.LEARN_MORE, null);
            var invalidLink = new MetaAdCreateRequest.Ad("광고", 40L, 41L, valid.ad().imageUrl(), url,
                    "문구", "제목", null, MetaAdCreateRequest.CallToAction.LEARN_MORE, null);
            for (var ad : List.of(invalidImage, invalidLink)) {
                assertThatThrownBy(() -> business.createAd(10L, 30L, 2L,
                        new MetaAdCreateRequest(valid.campaign(), valid.adSet(), ad)))
                        .isInstanceOfSatisfying(ApiException.class, exception ->
                                assertThat(exception.getCodeIfs()).isEqualTo(ApiCode.BAD_REQUEST));
            }
        }

        verifyNoInteractions(client);
    }

    @Test
    void storedImageKeyIsResolvedToFreshUrlAfterAuthorizationAndBeforeRemoteWrites() {
        var request = withImageSource(null, IMAGE_KEY);
        stubAccountAndIdentity(request);
        stubCreatedObjects();
        var signedUrl = "https://bucket.s3.ap-northeast-2.amazonaws.com/image.jpg?X-Amz-Signature=fresh";
        when(images.resolveImageUrl(10L, 30L, IMAGE_KEY)).thenReturn(signedUrl);

        assertThat(business.createAd(10L, 30L, 2L, request).adId()).isEqualTo("1004");

        var order = inOrder(service, images, client);
        order.verify(service).getAdAccountForManagement(10L, 30L, 2L);
        order.verify(service).getAdIdentity(10L, 2L, ACCOUNT, 40L, 41L);
        order.verify(images).resolveImageUrl(10L, 30L, IMAGE_KEY);
        order.verify(client).listPromotablePages("act_123", "shared-secret-token");
        order.verify(service).verifyManagementUnchanged(10L, 2L, ACCOUNT);
        order.verify(client).createCampaign(anyString(), anyString(), anyString(), anyString(), anyList(), anyList());
        verify(client).createImageCreative("act_123", "shared-secret-token",
                new CreativeSpec("상품 광고", "9001", "9002", "https://shop.example.com/product/1",
                        signedUrl, "상품을 확인하세요", "신상품", "상품 설명", "LEARN_MORE"));
        verifyNoMoreInteractions(images);
    }

    @Test
    void missingOrAmbiguousImageSourceIsRejectedBeforeAuthorizationOrStorageCalls() {
        for (var request : List.of(withImageSource(null, null), withImageSource(" ", " "),
                withImageSource("https://cdn.example.com/product.jpg", IMAGE_KEY))) {
            assertThatThrownBy(() -> business.createAd(10L, 30L, 2L, request))
                    .isInstanceOfSatisfying(ApiException.class, exception ->
                            assertThat(exception.getCodeIfs()).isEqualTo(ApiCode.BAD_REQUEST));
        }

        verifyNoInteractions(service, images, client);
    }

    @Test
    void missingImageOrStorageFailureCannotLeavePartiallyCreatedMetaObjects() {
        var request = withImageSource(null, IMAGE_KEY);
        stubAccountAndIdentity(request);
        var missing = new ApiException(ApiCode.BAD_REQUEST, "이미지를 찾을 수 없습니다.");
        var storageFailure = new ApiException(ApiCode.SERVER_ERROR, "이미지를 사용할 수 없습니다.");
        when(images.resolveImageUrl(10L, 30L, IMAGE_KEY)).thenThrow(missing).thenThrow(storageFailure);

        assertThatThrownBy(() -> business.createAd(10L, 30L, 2L, request)).isSameAs(missing);
        assertThatThrownBy(() -> business.createAd(10L, 30L, 2L, request)).isSameAs(storageFailure);

        verifyNoInteractions(client);
    }

    @Test
    void imageFromAnotherWorkspaceIsRejectedBeforeRemoteCalls() {
        var foreignKey = IMAGE_KEY.replace("workspaces/10/", "workspaces/99/");
        var request = withImageSource(null, foreignKey);
        stubAccountAndIdentity(request);
        var forbidden = new ApiException(ApiCode.BAD_REQUEST, "선택한 계정의 이미지가 아닙니다.");
        when(images.resolveImageUrl(10L, 30L, foreignKey)).thenThrow(forbidden);

        assertThatThrownBy(() -> business.createAd(10L, 30L, 2L, request)).isSameAs(forbidden);

        verifyNoInteractions(client);
    }

    @Test
    void unauthorizedAccountCannotResolveAnImageKey() {
        var denied = new ApiException(UserErrorCode.USER_PERMISSION_DENY);
        when(service.getAdAccountForManagement(10L, 30L, 2L)).thenThrow(denied);

        assertThatThrownBy(() -> business.createAd(10L, 30L, 2L, withImageSource(null, IMAGE_KEY))).isSameAs(denied);

        verifyNoInteractions(images, client);
    }

    @Test
    void imageUploadUsesAuthorizedWorkspaceAccountAndRechecksAccessBeforeReturning() {
        var file = new MockMultipartFile("file", "image.jpg", "image/jpeg", new byte[]{1, 2, 3});
        var expected = new MetaAdImageUploadResponse(IMAGE_KEY, "https://bucket.s3.amazonaws.com/image.jpg?signature=test",
                Instant.parse("2026-09-22T12:00:00Z"), "image/jpeg", 3);
        when(service.getAdAccountForManagement(10L, 30L, 2L)).thenReturn(ACCOUNT);
        when(images.upload(10L, 30L, file)).thenReturn(expected);

        assertThat(business.uploadImage(10L, 30L, 2L, file)).isSameAs(expected);

        var order = inOrder(service, images);
        order.verify(service).getAdAccountForManagement(10L, 30L, 2L);
        order.verify(images).upload(10L, 30L, file);
        order.verify(service).verifyManagementUnchanged(10L, 2L, ACCOUNT);
        verifyNoMoreInteractions(service, images);
        verifyNoInteractions(client);
    }

    @Test
    void revokedAccessWhileUploadingPreventsReturningImageUrl() {
        var file = new MockMultipartFile("file", "image.jpg", "image/jpeg", new byte[]{1, 2, 3});
        when(service.getAdAccountForManagement(10L, 30L, 2L)).thenReturn(ACCOUNT);
        var denied = new ApiException(UserErrorCode.USER_PERMISSION_DENY);
        doThrow(denied).when(service).verifyManagementUnchanged(10L, 2L, ACCOUNT);

        assertThatThrownBy(() -> business.uploadImage(10L, 30L, 2L, file)).isSameAs(denied);

        verify(images).upload(10L, 30L, file);
        verifyNoInteractions(client);
    }

    private MetaAdCreateRequest withImageSource(String imageUrl, String imageKey) {
        var valid = trafficRequest();
        var ad = valid.ad();
        return new MetaAdCreateRequest(valid.campaign(), valid.adSet(), new MetaAdCreateRequest.Ad(
                ad.name(), ad.pageAssetId(), ad.instagramAssetId(), imageUrl, ad.linkUrl(), ad.message(), ad.headline(),
                ad.description(), ad.callToAction(), imageKey));
    }

    private void stubAccountAndIdentity(MetaAdCreateRequest request) {
        when(service.getAdAccountForManagement(10L, 30L, 2L)).thenReturn(ACCOUNT);
        when(service.getAdIdentity(10L, 2L, ACCOUNT, request.ad().pageAssetId(), request.ad().instagramAssetId()))
                .thenReturn(IDENTITY);
    }

    private void stubCreatedObjects() {
        when(client.listPromotablePages("act_123", "shared-secret-token"))
                .thenReturn(List.of(new MetaGraphClient.DiscoveredAsset("9001", "페이지", PlatformType.FACEBOOK,
                        AssetType.PAGE, "9001")));
        when(client.createCampaign(anyString(), anyString(), anyString(), anyString(), anyList(), anyList()))
                .thenReturn(new CreatedCampaign("1001"));
        when(client.createAdSet(anyString(), anyString(), anyString(), any(AdSetSpec.class)))
                .thenReturn(new CreatedAdSet("1002"));
        when(client.createImageCreative(anyString(), anyString(), any(CreativeSpec.class)))
                .thenReturn(new CreatedCreative("1003"));
        when(client.createAd(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new CreatedAd("1004"));
    }

    private MetaAdCreateRequest trafficRequest() {
        return new MetaAdCreateRequest(
                new MetaCampaignCreateRequest("유입 캠페인", MetaCampaignCreateRequest.Objective.OUTCOME_TRAFFIC, List.of(), null),
                new MetaAdCreateRequest.AdSet("한국 광고 세트", 10000L, List.of("KR"), 20, 50, null),
                new MetaAdCreateRequest.Ad("상품 광고", 40L, 41L, "https://cdn.example.com/product.jpg",
                        "https://shop.example.com/product/1", "상품을 확인하세요", "신상품", "상품 설명", MetaAdCreateRequest.CallToAction.LEARN_MORE, null));
    }
}
