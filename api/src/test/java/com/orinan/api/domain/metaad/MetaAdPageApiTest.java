package com.orinan.api.domain.metaad;

import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.metaad.business.MetaAdPageBusiness;
import com.orinan.api.domain.metaad.controller.MetaAdPageApiController;
import com.orinan.api.domain.metaad.controller.model.MetaAdPageSaveRequest;
import com.orinan.api.domain.platformconnection.controller.model.PlatformAssetResponse;
import com.orinan.api.domain.platformconnection.meta.MetaGraphClient;
import com.orinan.api.domain.user.controller.model.UserResponse;
import com.orinan.api.domain.user.converter.UserConverter;
import com.orinan.api.domain.user.exception.UserErrorCode;
import com.orinan.api.domain.user.service.UserService;
import com.orinan.api.exceptionhandler.ApiExceptionHandler;
import com.orinan.api.exceptionhandler.GlobalExceptionHandler;
import com.orinan.api.exceptionhandler.ValidExceptionHandler;
import com.orinan.api.resolver.UserSessionResolver;
import com.orinan.db.platformasset.enums.AssetType;
import com.orinan.db.platformasset.enums.PlatformType;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MetaAdPageApiTest {

    private static final String PATH = "/api/workspaces/10/meta/ad-accounts/30/pages";
    private final MetaAdPageBusiness business = mock(MetaAdPageBusiness.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        var users = mock(UserService.class);
        var converter = mock(UserConverter.class);
        var user = UserEntity.builder().id(2L).build();
        when(users.findByIdAndStatusWithThrow(2L, UserStatus.REGISTERED)).thenReturn(user);
        when(converter.toResponse(user)).thenReturn(UserResponse.builder().id(2L).build());
        var mapper = JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();
        mvc = MockMvcBuilders.standaloneSetup(new MetaAdPageApiController(business))
                .setCustomArgumentResolvers(new UserSessionResolver(users, converter))
                .setMessageConverters(new JacksonJsonHttpMessageConverter(mapper))
                .setControllerAdvice(new ValidExceptionHandler(), new ApiExceptionHandler(), new GlobalExceptionHandler())
                .build();
    }

    @Test
    void accountPageListUsesSavedAccountIdAndSnakeCaseWithoutTokenFields() throws Exception {
        when(business.getPages(10L, 30L, 2L)).thenReturn(List.of(new MetaGraphClient.DiscoveredAsset(
                "9001", "페이지", PlatformType.FACEBOOK, AssetType.PAGE, "9001")));

        var response = mvc.perform(get(PATH).requestAttr("userId", 2L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body[0].external_id").value("9001"))
                .andExpect(jsonPath("$.body[0].platform_type").value("FACEBOOK"))
                .andExpect(jsonPath("$.body[0].asset_type").value("PAGE"))
                .andExpect(jsonPath("$.body[0].facebook_page_id").value("9001"))
                .andReturn().getResponse();

        verify(business).getPages(10L, 30L, 2L);
        assertThat(response.getContentAsString()).doesNotContain("access_token", "accessToken");
    }

    @Test
    void savesExternalPageIdAndReturnsInternalPageIdForAdCreation() throws Exception {
        when(business.savePage(10L, 30L, 2L, new MetaAdPageSaveRequest("9001"))).thenReturn(List.of(
                new PlatformAssetResponse(44L, "9001", "페이지", PlatformType.FACEBOOK, AssetType.PAGE, "9001")));

        mvc.perform(post(PATH).requestAttr("userId", 2L).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"external_id\":\"9001\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body[0].id").value(44))
                .andExpect(jsonPath("$.body[0].external_id").value("9001"));
    }

    @Test
    void validatesPageIdBeforeCallingBusiness() throws Exception {
        for (String body : List.of("{}", "{\"external_id\":null}", "{\"external_id\":\" \"}",
                "{\"external_id\":\"act_123\"}", "{\"external_id\":\"123/ads\"}",
                "{\"external_id\":\"123456789012345678901234567890123\"}")) {
            mvc.perform(post(PATH).requestAttr("userId", 2L).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(business);
    }

    @Test
    void authorizationFailuresAreForbiddenForBothPageActions() throws Exception {
        when(business.getPages(10L, 30L, 2L)).thenThrow(new ApiException(UserErrorCode.USER_PERMISSION_DENY));
        when(business.savePage(eq(10L), eq(30L), eq(2L), any()))
                .thenThrow(new ApiException(UserErrorCode.USER_PERMISSION_DENY));

        mvc.perform(get(PATH).requestAttr("userId", 2L)).andExpect(status().isForbidden());
        mvc.perform(post(PATH).requestAttr("userId", 2L).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"external_id\":\"9001\"}"))
                .andExpect(status().isForbidden());
    }
}
