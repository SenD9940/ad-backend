package com.orinan.api.domain.platformconnection.naver.selftest;

import com.orinan.api.domain.platformconnection.controller.model.PlatformConnectionResponse;
import com.orinan.api.domain.user.controller.model.UserResponse;
import com.orinan.api.domain.user.converter.UserConverter;
import com.orinan.api.domain.user.service.UserService;
import com.orinan.api.exceptionhandler.ApiExceptionHandler;
import com.orinan.api.resolver.UserSessionResolver;
import com.orinan.db.platformconnection.enums.ProviderType;
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

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class NaverSelfTestControllerTest {
    private final NaverSelfTestBusiness business = mock(NaverSelfTestBusiness.class);
    private MockMvc mvc;

    @BeforeEach void setUp() {
        var users = mock(UserService.class); var converter = mock(UserConverter.class);
        var user = UserEntity.builder().id(2L).build();
        when(users.findByIdAndStatusWithThrow(2L,UserStatus.REGISTERED)).thenReturn(user);
        when(converter.toResponse(user)).thenReturn(UserResponse.builder().id(2L).build());
        mvc = MockMvcBuilders.standaloneSetup(new NaverSelfTestController(business))
                .setCustomArgumentResolvers(new UserSessionResolver(users,converter))
                .setMessageConverters(new JacksonJsonHttpMessageConverter(JsonMapper.builder()
                        .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build()))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @Test void statusContainsOnlyReadinessAndNoSecrets() throws Exception {
        when(business.availability(1L,2L)).thenReturn(new NaverSelfTestPolicy.Availability(true,null));
        var response = mvc.perform(get("/api/workspaces/1/connections/naver/self-test").requestAttr("userId",2L))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.body.available").value(true)).andReturn().getResponse();
        assertThat(response.getContentAsString()).doesNotContain("app_id","app_secret","client_secret","access_token");
    }

    @Test void emptyObjectAndAbsentBodyCreateConnectionUsingServerAppOnly() throws Exception {
        when(business.connect(1L,2L)).thenReturn(new PlatformConnectionResponse(7L,1L,ProviderType.NAVER,
                "verified-uid","seller",false,null,List.of()));
        mvc.perform(post("/api/workspaces/1/connections/naver/self-test").requestAttr("userId",2L)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.body.connection_mode").value("MANUAL"))
                .andExpect(jsonPath("$.body.external_account_id").value("verified-uid"));
        mvc.perform(post("/api/workspaces/1/connections/naver/self-test").requestAttr("userId",2L))
                .andExpect(status().isOk());
        verify(business,times(2)).connect(1L,2L);
    }

    @Test void rejectsBrowserCredentialsTargetSellerAndReconnectIdsRatherThanIgnoringThem() throws Exception {
        for (String body : List.of("{\"app_id\":\"override\"}","{\"app_secret\":\"override\"}",
                "{\"account_id\":\"other-seller\"}","{\"token_type\":\"SELLER\"}",
                "{\"connection_id\":99}","[]","\"value\"")) {
            mvc.perform(post("/api/workspaces/1/connections/naver/self-test").requestAttr("userId",2L)
                            .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(business);
    }
}
