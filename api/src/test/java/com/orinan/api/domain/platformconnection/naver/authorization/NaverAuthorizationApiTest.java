package com.orinan.api.domain.platformconnection.naver.authorization;
import com.orinan.api.domain.platformconnection.naver.solution.NaverAuthorizationProvider;
import com.orinan.api.domain.user.controller.model.UserResponse;
import com.orinan.api.domain.user.converter.UserConverter;
import com.orinan.api.domain.user.service.UserService;
import com.orinan.api.exceptionhandler.*;
import com.orinan.api.resolver.UserSessionResolver;
import com.orinan.db.user.UserEntity;
import com.orinan.db.user.enums.UserStatus;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class NaverAuthorizationApiTest {
    NaverAuthorizationService service=mock(NaverAuthorizationService.class);
    NaverAuthorizationProvider provider=mock(NaverAuthorizationProvider.class);
    MockMvc mvc;
    String id="a".repeat(43);
    @BeforeEach void setup() {
        var users=mock(UserService.class);var converter=mock(UserConverter.class);var user=UserEntity.builder().id(7L).build();
        when(users.findByIdAndStatusWithThrow(7L,UserStatus.REGISTERED)).thenReturn(user);when(converter.toResponse(user)).thenReturn(UserResponse.builder().id(7L).build());
        when(service.publicOrigin()).thenReturn("https://app.example.com");
        mvc=MockMvcBuilders.standaloneSetup(new NaverAuthorizationApiController(service),new NaverAuthorizationCallbackController(service,provider))
            .setCustomArgumentResolvers(new UserSessionResolver(users,converter)).setMessageConverters(new JacksonJsonHttpMessageConverter(JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build()))
            .setControllerAdvice(new ValidExceptionHandler(),new ApiExceptionHandler()).build();
    }
    @Test void startImmediatelySetsSecureHttpOnlyAttemptCookieAndReturnsOnlySafeFields() throws Exception {
        when(service.start(1L,7L,null,null,"")).thenReturn(new NaverAuthorizationService.Started(response("WAITING_AUTH"),"browser-secret"));
        var result=mvc.perform(post("/api/workspaces/1/connections/naver/authorizations").requestAttr("userId",7L).header("Origin","https://app.example.com").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.body.attempt_id").value(id)).andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse();
        assertThat(result.getHeader("Set-Cookie")).contains("naver_attempt_"+id+"=browser-secret","Secure","HttpOnly","SameSite=Lax","Path=/");
        assertThat(result.getContentAsString()).doesNotContain("browser-secret","access_token","jwe");
    }
    @Test void externalOriginCannotStartOrMutateAttempt() throws Exception {
        mvc.perform(post("/api/workspaces/1/connections/naver/authorizations").requestAttr("userId",7L).header("Origin","https://attacker.example").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden());verify(service,never()).start(any(),any(),any(),any(),any());
    }
    @Test void completeForwardsExplicitRevisionKeyAndBrowserAndReturns202ForUnknownOutcome() throws Exception {
        when(service.complete(1L,7L,id,"browser-secret",2,"idempotency-key-1234")).thenReturn(response("RECONCILING"));
        mvc.perform(post("/api/workspaces/1/connections/naver/authorizations/"+id+"/complete").requestAttr("userId",7L).header("Origin","https://app.example.com").header("Idempotency-Key","idempotency-key-1234")
            .cookie(new Cookie("naver_attempt_"+id,"browser-secret")).contentType(MediaType.APPLICATION_JSON).content("{\"review_revision\":2}"))
            .andExpect(status().isAccepted()).andExpect(jsonPath("$.body.status").value("RECONCILING"));
    }
    @Test void globalResolverUsesAuthenticatedIdentityAndStoredWorkspace() throws Exception {
        when(service.get(null,7L,id)).thenReturn(response("REVIEW_REQUIRED"));
        mvc.perform(get("/api/integrations/naver/authorizations/"+id).requestAttr("userId",7L)).andExpect(status().isOk()).andExpect(jsonPath("$.body.workspace_id").value(1));
        verify(service).get(null,7L,id);
    }
    @Test void callbackRejectsAmbiguousParametersWithoutCallingProviderInterpreter() throws Exception {
        when(service.failureUrl()).thenReturn("https://app.example.com/settings/integrations/naver/callback?error=naver_authentication_failed");
        mvc.perform(get("/open-api/integrations/naver/callback").param("state","one","two").param("token","sensitive-proof"))
            .andExpect(status().isSeeOther()).andExpect(header().string("Referrer-Policy","no-referrer"));
        verify(provider,never()).readCallback(any());verify(service,never()).callback(any(),any(),any());
    }
    @Test void publicLaunchNeverSetsBrowserBindingForRecipient() throws Exception {
        String ticket=id+"."+"b".repeat(43);when(service.launch(ticket,"")).thenThrow(new com.orinan.api.common.exception.ApiException(NaverAuthorizationCode.FORBIDDEN));
        mvc.perform(get("/open-api/integrations/naver/launch").param("ticket",ticket)).andExpect(status().isForbidden()).andExpect(header().doesNotExist("Set-Cookie"));
    }
    private NaverAuthorizationResponse response(String status) {return new NaverAuthorizationResponse(id,1L,status,2,null,null,null,"WAIT",Instant.now().plusSeconds(600),null,null);}
}
