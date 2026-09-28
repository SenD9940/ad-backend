package com.orinan.api.domain.metaad;

import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.metaad.business.MetaAdBusiness;
import com.orinan.api.domain.metaad.controller.MetaAdApiController;
import com.orinan.api.domain.metaad.controller.model.MetaAdBudgetUpdateRequest;
import com.orinan.api.domain.metaad.controller.model.MetaAdUpdateRequest;
import com.orinan.api.domain.metaad.service.MetaAdImageService;
import com.orinan.api.domain.metaad.service.MetaAdService;
import com.orinan.api.domain.platformconnection.meta.MetaGraphClient;
import com.orinan.api.domain.platformconnection.meta.MetaGraphClient.UpdatedAdObject;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static com.orinan.api.domain.metaad.controller.model.MetaAdUpdateRequest.Status.ACTIVE;
import static com.orinan.api.domain.metaad.controller.model.MetaAdUpdateRequest.Status.PAUSED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MetaAdUpdateApiTest {

    private static final String PREFIX = "/api/workspaces/10/meta/ad-accounts/30";
    private static final String AD_PATH = PREFIX + "/ads/1004";
    private static final String AD_SET_PATH = PREFIX + "/ad-sets/1002";
    private static final String CAMPAIGN_PATH = PREFIX + "/campaigns/1001";
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
    void activatesAdWithAuthenticatedWorkspaceAccountAndMetaId() throws Exception {
        var request = new MetaAdUpdateRequest(null, ACTIVE);
        when(business.updateAd(10L, 30L, 2L, "1004", request)).thenReturn(new UpdatedAdObject("1004", true));

        var response = mvc.perform(patch(AD_PATH).requestAttr("userId", 2L)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.id").value("1004"))
                .andExpect(jsonPath("$.body.success").value(true))
                .andReturn().getResponse();

        verify(business).updateAd(10L, 30L, 2L, "1004", request);
        verifyNoMoreInteractions(business);
        assertThat(response.getContentAsString()).doesNotContain("access_token", "accessToken", "client_secret");
    }

    @Test
    void pausesAdAndRenamesItWithoutSendingBudget() throws Exception {
        mvc.perform(patch(AD_PATH).requestAttr("userId", 2L).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"수정 광고\",\"status\":\"PAUSED\"}"))
                .andExpect(status().isOk());

        verify(business).updateAd(10L, 30L, 2L, "1004", new MetaAdUpdateRequest("수정 광고", PAUSED));
    }

    @Test
    void editsAdSetBudgetWithSnakeCaseAndLeavesUnspecifiedFieldsNull() throws Exception {
        var request = new MetaAdBudgetUpdateRequest(null, null, 20000L);
        when(business.updateAdSet(10L, 30L, 2L, "1002", request)).thenReturn(new UpdatedAdObject("1002", true));

        mvc.perform(patch(AD_SET_PATH).requestAttr("userId", 2L).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"daily_budget\":20000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.id").value("1002"))
                .andExpect(jsonPath("$.body.success").value(true));

        verify(business).updateAdSet(10L, 30L, 2L, "1002", request);
    }

    @Test
    void editsCampaignNameStatusAndDailyBudgetTogether() throws Exception {
        var request = new MetaAdBudgetUpdateRequest("수정 캠페인", ACTIVE, 30000L);
        when(business.updateCampaign(10L, 30L, 2L, "1001", request)).thenReturn(new UpdatedAdObject("1001", true));

        mvc.perform(patch(CAMPAIGN_PATH).requestAttr("userId", 2L).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"수정 캠페인\",\"status\":\"ACTIVE\",\"daily_budget\":30000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.id").value("1001"))
                .andExpect(jsonPath("$.body.success").value(true));

        verify(business).updateCampaign(10L, 30L, 2L, "1001", request);
    }

    @Test
    void acceptsNameOnlyAndSkipsExplicitlyNullOptionalFields() throws Exception {
        mvc.perform(patch(AD_PATH).requestAttr("userId", 2L).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"새 광고\",\"status\":null}"))
                .andExpect(status().isOk());
        mvc.perform(patch(AD_SET_PATH).requestAttr("userId", 2L).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"새 광고 세트\",\"status\":null,\"daily_budget\":null}"))
                .andExpect(status().isOk());
        mvc.perform(patch(CAMPAIGN_PATH).requestAttr("userId", 2L).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"새 캠페인\"}"))
                .andExpect(status().isOk());

        verify(business).updateAd(10L, 30L, 2L, "1004", new MetaAdUpdateRequest("새 광고", null));
        verify(business).updateAdSet(10L, 30L, 2L, "1002", new MetaAdBudgetUpdateRequest("새 광고 세트", null, null));
        verify(business).updateCampaign(10L, 30L, 2L, "1001", new MetaAdBudgetUpdateRequest("새 캠페인", null, null));
    }

    @Test
    void rejectsEmptyNullBlankAndUnsupportedStatusPatchesBeforeBusiness() throws Exception {
        for (String path : List.of(AD_PATH, AD_SET_PATH, CAMPAIGN_PATH)) {
            for (String body : List.of("{}", "null", "{\"name\":null,\"status\":null}",
                    "{\"name\":\"\"}", "{\"name\":\" \",\"status\":\"ACTIVE\"}",
                    "{\"name\":\"" + "x".repeat(256) + "\"}",
                    "{\"status\":\"DELETED\"}", "{\"status\":\"ARCHIVED\"}",
                    "{\"status\":\"active\"}", "{\"status\":123}",
                    "{\"status\":0}", "{\"status\":1}", "{\"status\":\"0\"}", "{\"status\":\"1\"}")) {
                mvc.perform(patch(path).requestAttr("userId", 2L).contentType(MediaType.APPLICATION_JSON).content(body))
                        .andExpect(status().isBadRequest());
            }
        }

        verifyNoInteractions(business);
    }

    @Test
    void rejectsNonpositiveFractionalAndOverflowBudgetsBeforeBusiness() throws Exception {
        for (String path : List.of(AD_SET_PATH, CAMPAIGN_PATH)) {
            for (String body : List.of("{\"daily_budget\":0}", "{\"daily_budget\":-1}",
                    "{\"daily_budget\":1.5}", "{\"daily_budget\":1.0}", "{\"daily_budget\":\"10000\"}",
                    "{\"daily_budget\":9223372036854775808}",
                    "{\"daily_budget\":null}", "{\"daily_budget\":\"invalid\"}")) {
                mvc.perform(patch(path).requestAttr("userId", 2L).contentType(MediaType.APPLICATION_JSON).content(body))
                        .andExpect(status().isBadRequest());
            }
        }

        verifyNoInteractions(business);
    }

    @Test
    void rejectsUnsupportedEditFieldsEvenWhenAValidFieldIsAlsoPresent() throws Exception {
        for (String body : List.of("{\"status\":\"ACTIVE\",\"daily_budget\":10000}",
                "{\"status\":\"ACTIVE\",\"objective\":\"OUTCOME_TRAFFIC\"}",
                "{\"name\":\"광고\",\"image_url\":\"https://example.com/image.jpg\"}")) {
            mvc.perform(patch(AD_PATH).requestAttr("userId", 2L).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        for (String path : List.of(AD_SET_PATH, CAMPAIGN_PATH)) {
            for (String body : List.of("{\"status\":\"ACTIVE\",\"dailyBudget\":10000}",
                    "{\"status\":\"ACTIVE\",\"lifetime_budget\":10000}",
                    "{\"status\":\"ACTIVE\",\"objective\":\"OUTCOME_TRAFFIC\"}")) {
                mvc.perform(patch(path).requestAttr("userId", 2L).contentType(MediaType.APPLICATION_JSON).content(body))
                        .andExpect(status().isBadRequest());
            }
        }

        verifyNoInteractions(business);
    }

    @Test
    void rejectsMalformedMetaIdsThroughRealBusinessBeforeDatabaseOrRemoteAccess() throws Exception {
        var service = mock(MetaAdService.class);
        var client = mock(MetaGraphClient.class);
        var images = mock(MetaAdImageService.class);
        var validatingMvc = mvcFor(new MetaAdBusiness(service, client, images));
        for (String entity : List.of("ads", "ad-sets", "campaigns")) {
            validatingMvc.perform(patch(PREFIX + "/" + entity + "/act_123").requestAttr("userId", 2L)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACTIVE\"}"))
                    .andExpect(status().isBadRequest());
        }

        verifyNoInteractions(service, client, images);
    }

    @Test
    void returnsForbiddenForUnauthorizedWorkspaceMemberAndBadRequestForUnsupportedBudgetMode() throws Exception {
        when(business.updateAd(10L, 30L, 2L, "1004", new MetaAdUpdateRequest(null, ACTIVE)))
                .thenThrow(new ApiException(UserErrorCode.USER_PERMISSION_DENY));
        when(business.updateAdSet(10L, 30L, 2L, "1002", new MetaAdBudgetUpdateRequest(null, null, 10000L)))
                .thenThrow(new ApiException(ApiCode.BAD_REQUEST, "캠페인 예산을 수정해 주세요."));

        mvc.perform(patch(AD_PATH).requestAttr("userId", 2L).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(patch(AD_SET_PATH).requestAttr("userId", 2L).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"daily_budget\":10000}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result.result_code").value(ApiCode.BAD_REQUEST.getCode()));
    }

    private MockMvc mvcFor(MetaAdBusiness handler) {
        var mapper = JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .disable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
        return MockMvcBuilders.standaloneSetup(new MetaAdApiController(handler))
                .setCustomArgumentResolvers(new UserSessionResolver(users, converter))
                .setMessageConverters(new JacksonJsonHttpMessageConverter(mapper))
                .setControllerAdvice(new ValidExceptionHandler(), new ApiExceptionHandler(), new GlobalExceptionHandler())
                .build();
    }
}
