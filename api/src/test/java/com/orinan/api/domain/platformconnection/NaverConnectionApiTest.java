package com.orinan.api.domain.platformconnection;

import com.orinan.api.domain.platformconnection.business.NaverConnectionBusiness;
import com.orinan.api.domain.platformconnection.controller.NaverConnectionApiController;
import com.orinan.api.domain.platformconnection.controller.model.NaverConnectRequest;
import com.orinan.api.domain.platformconnection.controller.model.PlatformConnectionResponse;
import com.orinan.api.domain.platformconnection.naver.NaverCommerceClient.Channel;
import com.orinan.api.domain.platformconnection.naver.NaverCommerceClient;
import com.orinan.api.domain.platformconnection.service.NaverConnectionService;
import com.orinan.api.domain.platformconnection.service.PlatformConnectionService;
import com.orinan.api.domain.user.controller.model.UserResponse;
import com.orinan.api.domain.user.converter.UserConverter;
import com.orinan.api.domain.user.service.UserService;
import com.orinan.api.exceptionhandler.ValidExceptionHandler;
import com.orinan.api.exceptionhandler.ApiExceptionHandler;
import com.orinan.api.resolver.UserSessionResolver;
import com.orinan.db.naverconnection.enums.NaverTokenType;
import com.orinan.db.platformconnection.enums.ProviderType;
import com.orinan.db.user.UserEntity;
import com.orinan.db.user.enums.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(OutputCaptureExtension.class)
class NaverConnectionApiTest {

    private final NaverConnectionBusiness business = mock(NaverConnectionBusiness.class);
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

    private MockMvc mvcFor(NaverConnectionBusiness handler) {
        var mapper = JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();
        return MockMvcBuilders.standaloneSetup(new NaverConnectionApiController(handler))
                .setCustomArgumentResolvers(new UserSessionResolver(users, converter))
                .setMessageConverters(new JacksonJsonHttpMessageConverter(mapper))
                .setControllerAdvice(new ValidExceptionHandler(), new ApiExceptionHandler()).build();
    }

    @Test
    void connectAcceptsSnakeCaseAndDoesNotReturnCredentials() throws Exception {
        when(business.connect(eq(1L), eq(2L), any())).thenReturn(new PlatformConnectionResponse(
                3L, 1L, ProviderType.NAVER, "verified-uid", "seller-name", false, null, List.of()));
        String secret = "$2a$10$abcdefghijklmnopqrstuu";

        var response = mvc.perform(post("/api/workspaces/1/connections/naver").requestAttr("userId", 2L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"client_id\":\"client-id\",\"client_secret\":\"" + secret + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.workspace_id").value(1))
                .andExpect(jsonPath("$.body.provider_type").value("NAVER"))
                .andExpect(jsonPath("$.body.external_account_id").value("verified-uid"))
                .andReturn().getResponse();
        var captor = ArgumentCaptor.forClass(NaverConnectRequest.class);
        verify(business).connect(eq(1L), eq(2L), captor.capture());
        assertThat(captor.getValue().getTokenType()).isEqualTo(NaverTokenType.SELF);
        assertThat(captor.getValue().getClientSecret()).isEqualTo(secret);
        assertThat(captor.getValue().toString()).doesNotContain(secret);
        assertThat(response.getContentAsString()).doesNotContain(secret, "client_secret", "access_token", "client_id");
    }

    @Test
    void invalidSecretIsNeverIncludedInValidationLogsOrResponse(CapturedOutput output) throws Exception {
        var client = new NaverCommerceClient(WebClient.builder().exchangeFunction(request -> {
            throw new AssertionError("Invalid credentials must not reach the network");
        }), JsonMapper.builder().build());
        var service = mock(NaverConnectionService.class);
        var validatingMvc = mvcFor(new NaverConnectionBusiness(mock(PlatformConnectionService.class), service, client));
        String secret = "sensitive-client-secret-".repeat(20);
        var response = validatingMvc.perform(post("/api/workspaces/1/connections/naver").requestAttr("userId", 2L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"client_id\":\"client-id\",\"client_secret\":\"" + secret + "\"}"))
                .andExpect(status().isBadRequest()).andReturn().getResponse();
        assertThat(response.getContentAsString()).doesNotContain("sensitive-client-secret-");
        assertThat(output.getAll()).doesNotContain("sensitive-client-secret-");
        verifyNoInteractions(service);
    }

    @Test
    void invalidChannelSelectionIsRejectedBeforeCallingBusiness() throws Exception {
        for (String body : List.of("{}", "{\"channel_nos\":[]}", "{\"channel_nos\":[null]}", "{\"channel_nos\":[0]}")) {
            mvc.perform(post("/api/workspaces/1/connections/3/naver/channels").requestAttr("userId", 2L)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(business);
    }

    @Test
    void channelsAreReturnedWithSnakeCaseWithoutInternalCredentials() throws Exception {
        when(business.getChannels(1L, 3L, 2L)).thenReturn(List.of(
                new Channel(42, "STOREFARM", "우리 스토어", "https://smartstore.naver.com/example")));
        mvc.perform(get("/api/workspaces/1/connections/3/naver/channels").requestAttr("userId", 2L))
                .andExpect(status().isOk()).andExpect(jsonPath("$.body[0].channel_no").value(42))
                .andExpect(jsonPath("$.body[0].channel_type").value("STOREFARM"))
                .andExpect(jsonPath("$.body[0].access_token").doesNotExist());
    }
}
