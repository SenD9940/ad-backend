package com.orinan.api.domain.metaad;

import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.metaad.business.MetaAdBusiness;
import com.orinan.api.domain.metaad.controller.MetaAdApiController;
import com.orinan.api.domain.metaad.controller.model.MetaAdCreateRequest;
import com.orinan.api.domain.metaad.controller.model.MetaAdCreateResponse;
import com.orinan.api.domain.metaad.controller.model.MetaAdImageUploadResponse;
import com.orinan.api.domain.metaad.controller.model.MetaCampaignCreateRequest;
import com.orinan.api.domain.metaad.exception.MetaAdCreationException;
import com.orinan.api.domain.metaad.service.MetaAdImageService;
import com.orinan.api.domain.metaad.service.MetaAdService;
import com.orinan.api.domain.platformconnection.meta.MetaGraphClient;
import com.orinan.api.domain.user.controller.model.UserResponse;
import com.orinan.api.domain.user.converter.UserConverter;
import com.orinan.api.domain.user.exception.UserErrorCode;
import com.orinan.api.domain.user.service.UserService;
import com.orinan.api.exceptionhandler.ApiExceptionHandler;
import com.orinan.api.exceptionhandler.GlobalExceptionHandler;
import com.orinan.api.exceptionhandler.ValidExceptionHandler;
import com.orinan.api.resolver.UserSessionResolver;
import com.orinan.db.user.UserEntity;
import com.orinan.db.user.enums.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MetaAdCreationApiTest {

    private static final String PATH = "/api/workspaces/10/meta/ad-accounts/30/ads";
    private static final String IMAGE_PATH = "/api/workspaces/10/meta/ad-accounts/30/images";
    private static final String IMAGE_KEY = "auto-threads/workspaces/10/meta/ad-accounts/30/images/9cf3db78-b05a-4e28-af20-c43946892038.jpg";
    private static final String BODY = """
            {
              "campaign":{"name":"유입 캠페인","objective":"OUTCOME_TRAFFIC","special_ad_categories":[]},
              "ad_set":{"name":"한국 광고 세트","daily_budget":10000,"countries":["KR"],"age_min":20,"age_max":50},
              "ad":{"name":"상품 광고","page_asset_id":40,"instagram_asset_id":41,
                    "image_url":"https://cdn.example.com/product.jpg","link_url":"https://shop.example.com/product/1",
                    "message":"상품을 확인하세요","headline":"신상품","description":"상품 설명","call_to_action":"LEARN_MORE"}
            }
            """;
    private final MetaAdBusiness business = mock(MetaAdBusiness.class);
    private final UserService users = mock(UserService.class);
    private final UserConverter converter = mock(UserConverter.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        var user = UserEntity.builder().id(2L).build();
        when(users.findByIdAndStatusWithThrow(2L, UserStatus.REGISTERED)).thenReturn(user);
        when(converter.toResponse(user)).thenReturn(UserResponse.builder().id(2L).build());
        mvc = mvcFor(business);
    }

    @Test
    void acceptsNestedSnakeCaseRequestAndReturnsEveryCreatedId() throws Exception {
        var expected = new MetaAdCreateRequest(
                new MetaCampaignCreateRequest("유입 캠페인", MetaCampaignCreateRequest.Objective.OUTCOME_TRAFFIC, List.of(), null),
                new MetaAdCreateRequest.AdSet("한국 광고 세트", 10000L, List.of("KR"), 20, 50, null),
                new MetaAdCreateRequest.Ad("상품 광고", 40L, 41L, "https://cdn.example.com/product.jpg",
                        "https://shop.example.com/product/1", "상품을 확인하세요", "신상품", "상품 설명", MetaAdCreateRequest.CallToAction.LEARN_MORE, null));
        when(business.createAd(10L, 30L, 2L, expected)).thenReturn(new MetaAdCreateResponse(30L, "act_123",
                "1001", "1002", "1003", "1004", MetaAdCreateResponse.Status.CREATED, null, "생성 완료"));

        var response = mvc.perform(post(PATH).requestAttr("userId", 2L).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.asset_id").value(30))
                .andExpect(jsonPath("$.body.ad_account_id").value("act_123"))
                .andExpect(jsonPath("$.body.campaign_id").value("1001"))
                .andExpect(jsonPath("$.body.ad_set_id").value("1002"))
                .andExpect(jsonPath("$.body.creative_id").value("1003"))
                .andExpect(jsonPath("$.body.ad_id").value("1004"))
                .andExpect(jsonPath("$.body.status").value("CREATED"))
                .andExpect(jsonPath("$.body.failed_step").isEmpty())
                .andExpect(jsonPath("$.body.campaignId").doesNotExist())
                .andReturn().getResponse();

        assertThat(response.getContentAsString()).doesNotContain("access_token", "accessToken", "client_secret");
        verify(business).createAd(10L, 30L, 2L, expected);
    }

    @Test
    void validatesNestedRequiredFieldsBudgetTargetingAndImageCreativeBeforeCallingBusiness() throws Exception {
        var invalidBodies = List.of(
                "{}",
                BODY.replace("\"campaign\":{", "\"ignored_campaign\":{"),
                BODY.replace("\"ad_set\":{", "\"ignored_ad_set\":{"),
                BODY.replace("\"ad\":{", "\"ignored_ad\":{"),
                BODY.replace("\"daily_budget\":10000", "\"daily_budget\":0"),
                BODY.replace("\"daily_budget\":10000", "\"daily_budget\":-100"),
                BODY.replace("\"countries\":[\"KR\"]", "\"countries\":[]"),
                BODY.replace("\"countries\":[\"KR\"]", "\"countries\":[null]"),
                BODY.replace("\"countries\":[\"KR\"]", "\"countries\":[\"kr\"]"),
                BODY.replace("\"age_min\":20", "\"age_min\":17"),
                BODY.replace("\"age_max\":50", "\"age_max\":66"),
                BODY.replace("\"age_max\":50", "\"age_max\":50,\"pixel_id\":\"invalid\""),
                BODY.replace("\"page_asset_id\":40", "\"page_asset_id\":null"),
                BODY.replace("\"page_asset_id\":40", "\"page_asset_id\":0"),
                BODY.replace("\"instagram_asset_id\":41", "\"instagram_asset_id\":-1"),
                BODY.replace("https://cdn.example.com/product.jpg", " "),
                BODY.replace("\"image_url\":\"https://cdn.example.com/product.jpg\",", ""),
                BODY.replace("\"image_url\":\"https://cdn.example.com/product.jpg\"", "\"image_url\":null"),
                BODY.replace("\"image_url\":\"https://cdn.example.com/product.jpg\"", "\"image_key\":\" \""),
                BODY.replace("\"image_url\":\"https://cdn.example.com/product.jpg\"", "\"image_key\":\"" + "x".repeat(1025) + "\""),
                BODY.replace("\"image_url\":", "\"image_key\":\"" + IMAGE_KEY + "\",\"image_url\":"),
                BODY.replace("https://shop.example.com/product/1", " "),
                BODY.replace("상품을 확인하세요", " "),
                BODY.replace("신상품", " "),
                BODY.replace("\"call_to_action\":\"LEARN_MORE\"", "\"call_to_action\":null"),
                BODY.replace("\"call_to_action\":\"LEARN_MORE\"", "\"call_to_action\":\"UNKNOWN\""));

        for (String body : invalidBodies) {
            mvc.perform(post(PATH).requestAttr("userId", 2L).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }

        verifyNoInteractions(business);
    }

    @Test
    void validatesSalesPixelAgeOrderAndHttpsUrlsThroughRealBusinessBeforeAnyRemoteWrites() throws Exception {
        var service = mock(MetaAdService.class);
        var client = mock(MetaGraphClient.class);
        var images = mock(MetaAdImageService.class);
        var validatingMvc = mvcFor(new MetaAdBusiness(service, client, images));
        var invalidBodies = List.of(
                BODY.replace("OUTCOME_TRAFFIC", "OUTCOME_SALES"),
                BODY.replace("OUTCOME_TRAFFIC", "OUTCOME_ENGAGEMENT"),
                BODY.replace("\"age_min\":20", "\"age_min\":60"),
                BODY.replace("https://cdn.example.com/product.jpg", "http://cdn.example.com/product.jpg"),
                BODY.replace("https://shop.example.com/product/1", "https://user:secret@shop.example.com/product/1"),
                BODY.replace("https://shop.example.com/product/1", "https://shop.example.com/product/1#fragment"));

        for (String body : invalidBodies) {
            validatingMvc.perform(post(PATH).requestAttr("userId", 2L).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }

        verifyNoInteractions(service, client, images);
    }

    @Test
    void acceptsUploadedImageKeyWithoutImageUrl() throws Exception {
        var body = BODY.replace("\"image_url\":\"https://cdn.example.com/product.jpg\"", "\"image_key\":\"" + IMAGE_KEY + "\"");

        mvc.perform(post(PATH).requestAttr("userId", 2L).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        var request = org.mockito.ArgumentCaptor.forClass(MetaAdCreateRequest.class);
        verify(business).createAd(eq(10L), eq(30L), eq(2L), request.capture());
        assertThat(request.getValue().ad().imageKey()).isEqualTo(IMAGE_KEY);
        assertThat(request.getValue().ad().imageUrl()).isNull();
    }

    @Test
    void uploadsMultipartFileWithAuthenticatedUserAndReturnsSnakeCaseImageMetadata() throws Exception {
        var file = new MockMultipartFile("file", "상품.jpg", "image/jpeg", new byte[]{1, 2, 3});
        when(business.uploadImage(10L, 30L, 2L, file)).thenReturn(new MetaAdImageUploadResponse(
                IMAGE_KEY, "https://bucket.s3.ap-northeast-2.amazonaws.com/image.jpg?X-Amz-Signature=test",
                Instant.parse("2026-09-22T12:00:00Z"), "image/jpeg", 3));

        mvc.perform(multipart(IMAGE_PATH).file(file).requestAttr("userId", 2L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.image_key").value(IMAGE_KEY))
                .andExpect(jsonPath("$.body.image_url").value("https://bucket.s3.ap-northeast-2.amazonaws.com/image.jpg?X-Amz-Signature=test"))
                .andExpect(jsonPath("$.body.expires_at").value("2026-09-22T12:00:00Z"))
                .andExpect(jsonPath("$.body.content_type").value("image/jpeg"))
                .andExpect(jsonPath("$.body.size").value(3))
                .andExpect(jsonPath("$.body.imageKey").doesNotExist());

        verify(business).uploadImage(10L, 30L, 2L, file);
    }

    @Test
    void missingMultipartFileReturnsBadRequestBeforeBusiness() throws Exception {
        mvc.perform(multipart(IMAGE_PATH).requestAttr("userId", 2L))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(business);
    }

    @Test
    void deniedImageUploadReturnsForbiddenWithoutStorageAccess() throws Exception {
        var service = mock(MetaAdService.class);
        var client = mock(MetaGraphClient.class);
        var images = mock(MetaAdImageService.class);
        when(service.getAdAccountForManagement(10L, 30L, 2L))
                .thenThrow(new ApiException(UserErrorCode.USER_PERMISSION_DENY));
        var uploadMvc = mvcFor(new MetaAdBusiness(service, client, images));
        var file = new MockMultipartFile("file", "image.jpg", "image/jpeg", new byte[]{1, 2, 3});

        uploadMvc.perform(multipart(IMAGE_PATH).file(file).requestAttr("userId", 2L))
                .andExpect(status().isForbidden());

        verifyNoInteractions(images, client);
    }

    @Test
    void metaRejectionReturnsPartialIdsAndFailedStepAlongsideHttpError() throws Exception {
        var partial = new MetaAdCreateResponse(30L, "act_123", "1001", null, null, null,
                MetaAdCreateResponse.Status.FAILED, MetaAdCreateResponse.Step.AD_SET, "광고 세트 생성 실패");
        when(business.createAd(eq(10L), eq(30L), eq(2L), any(MetaAdCreateRequest.class)))
                .thenThrow(new MetaAdCreationException(new ApiException(ApiCode.BAD_REQUEST), partial));

        mvc.perform(post(PATH).requestAttr("userId", 2L).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result.result_code").value(ApiCode.BAD_REQUEST.getCode()))
                .andExpect(jsonPath("$.body.campaign_id").value("1001"))
                .andExpect(jsonPath("$.body.ad_set_id").isEmpty())
                .andExpect(jsonPath("$.body.status").value("FAILED"))
                .andExpect(jsonPath("$.body.failed_step").value("AD_SET"))
                .andExpect(jsonPath("$.body.message").value("광고 세트 생성 실패"));
    }

    @Test
    void unknownOutcomeRetainsAllPreviouslyConfirmedIdsInErrorBody() throws Exception {
        var partial = new MetaAdCreateResponse(30L, "act_123", "1001", "1002", "1003", null,
                MetaAdCreateResponse.Status.UNKNOWN, MetaAdCreateResponse.Step.AD, "생성 여부 확인 필요");
        when(business.createAd(eq(10L), eq(30L), eq(2L), any(MetaAdCreateRequest.class)))
                .thenThrow(new MetaAdCreationException(new ApiException(ApiCode.SERVER_ERROR), partial));

        mvc.perform(post(PATH).requestAttr("userId", 2L).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.body.campaign_id").value("1001"))
                .andExpect(jsonPath("$.body.ad_set_id").value("1002"))
                .andExpect(jsonPath("$.body.creative_id").value("1003"))
                .andExpect(jsonPath("$.body.ad_id").isEmpty())
                .andExpect(jsonPath("$.body.status").value("UNKNOWN"))
                .andExpect(jsonPath("$.body.failed_step").value("AD"));
    }

    @Test
    void revokedMembershipReturnsForbiddenWhileKeepingExistingCampaignId() throws Exception {
        var partial = new MetaAdCreateResponse(30L, "act_123", "1001", null, null, null,
                MetaAdCreateResponse.Status.FAILED, MetaAdCreateResponse.Step.AD_SET, "권한 없음");
        when(business.createAd(eq(10L), eq(30L), eq(2L), any(MetaAdCreateRequest.class)))
                .thenThrow(new MetaAdCreationException(new ApiException(UserErrorCode.USER_PERMISSION_DENY), partial));

        mvc.perform(post(PATH).requestAttr("userId", 2L).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.body.campaign_id").value("1001"))
                .andExpect(jsonPath("$.body.status").value("FAILED"))
                .andExpect(jsonPath("$.body.failed_step").value("AD_SET"));
    }

    private MockMvc mvcFor(MetaAdBusiness handler) {
        var mapper = JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();
        return MockMvcBuilders.standaloneSetup(new MetaAdApiController(handler))
                .setCustomArgumentResolvers(new UserSessionResolver(users, converter))
                .setMessageConverters(new JacksonJsonHttpMessageConverter(mapper))
                .setControllerAdvice(new ValidExceptionHandler(), new ApiExceptionHandler(), new GlobalExceptionHandler())
                .build();
    }
}
