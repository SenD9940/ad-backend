package com.orinan.api.domain.platformconnection;

import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.platformconnection.business.PlatformConnectionBusiness;
import com.orinan.api.domain.platformconnection.controller.MetaOAuthCallbackController;
import com.orinan.api.domain.platformconnection.controller.PlatformConnectionApiController;
import com.orinan.api.domain.platformconnection.controller.model.PlatformConnectionResponse;
import com.orinan.api.domain.platformconnection.meta.MetaProperties;
import com.orinan.api.domain.user.controller.model.UserResponse;
import com.orinan.db.platformconnection.enums.ProviderType;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MetaOAuthControllerTest {

    private static final String FIRST_STATE = "a".repeat(43);
    private static final String SECOND_STATE = "b".repeat(43);
    private final PlatformConnectionBusiness business = mock(PlatformConnectionBusiness.class);
    private final MetaProperties properties = properties();

    @Test
    void authorizationSetsShortLivedHttpOnlyBrowserCookie() {
        var user = UserResponse.builder().id(2L).build();
        when(business.startMetaAuthorization(1L, 2L)).thenReturn(FIRST_STATE);
        when(business.authorizationUrl(FIRST_STATE)).thenReturn("https://www.facebook.com/oauth");
        var response = new PlatformConnectionApiController(business, properties).authorizeMeta(user, 1L);
        assertThat(response.getHeaders().getFirst(HttpHeaders.SET_COOKIE))
                .contains(MetaOAuthCallbackController.STATE_COOKIE_PREFIX + FIRST_STATE + "=" + FIRST_STATE,
                        "HttpOnly", "Secure", "SameSite=Lax", "Max-Age=600",
                        "Path=" + MetaOAuthCallbackController.CALLBACK_PATH);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getBody().getBody().authorizationUrl()).isEqualTo("https://www.facebook.com/oauth");
    }

    @Test
    void callbackRedirectContainsConnectionIdentityAndClearsCookie() throws Exception {
        when(business.completeMetaAuthorization(FIRST_STATE, FIRST_STATE, "private-code", null))
                .thenReturn(new PlatformConnectionResponse(3L, 1L, ProviderType.META, "fb-user", "name", false, null, List.of()));
        var mvc = MockMvcBuilders.standaloneSetup(new MetaOAuthCallbackController(business, properties)).build();
        var response = mvc.perform(get(MetaOAuthCallbackController.CALLBACK_PATH)
                        .param("state", FIRST_STATE).param("code", "private-code")
                        .cookie(stateCookie(FIRST_STATE)))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("https://app.example.com/meta/result?status=success&workspace_id=1&connection_id=3"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andReturn().getResponse();
        assertThat(response.getHeader(HttpHeaders.SET_COOKIE))
                .contains(MetaOAuthCallbackController.STATE_COOKIE_PREFIX + FIRST_STATE + "=", "Max-Age=0");
        assertThat(response.getHeader(HttpHeaders.LOCATION)).doesNotContain("private-code", "token", "cookie");
    }

    @Test
    void callbackErrorDoesNotExposeUpstreamDescriptionOrCredentials() throws Exception {
        when(business.completeMetaAuthorization(FIRST_STATE, null, null, "access_denied"))
                .thenThrow(new ApiException(ApiCode.BAD_REQUEST, "sensitive upstream description"));
        var mvc = MockMvcBuilders.standaloneSetup(new MetaOAuthCallbackController(business, properties)).build();
        var response = mvc.perform(get(MetaOAuthCallbackController.CALLBACK_PATH)
                        .param("state", FIRST_STATE).param("error", "access_denied"))
                .andExpect(status().isFound()).andReturn().getResponse();
        assertThat(response.getRedirectedUrl()).startsWith("https://app.example.com/meta/result?status=error&error_code=")
                .doesNotContain("sensitive", "state");
    }

    @Test
    void parallelCallbacksReadAndClearOnlyTheirOwnCookies() throws Exception {
        when(business.completeMetaAuthorization(FIRST_STATE, FIRST_STATE, "first-code", null))
                .thenReturn(new PlatformConnectionResponse(3L, 1L, ProviderType.META, "fb-first", "first", false, null, List.of()));
        when(business.completeMetaAuthorization(SECOND_STATE, SECOND_STATE, "second-code", null))
                .thenReturn(new PlatformConnectionResponse(4L, 2L, ProviderType.META, "fb-second", "second", false, null, List.of()));
        var mvc = MockMvcBuilders.standaloneSetup(new MetaOAuthCallbackController(business, properties)).build();
        var first = mvc.perform(get(MetaOAuthCallbackController.CALLBACK_PATH)
                        .param("state", FIRST_STATE).param("code", "first-code")
                        .cookie(stateCookie(SECOND_STATE), stateCookie(FIRST_STATE)))
                .andExpect(status().isFound()).andReturn().getResponse();
        assertThat(first.getHeaders(HttpHeaders.SET_COOKIE)).hasSize(1);
        assertThat(first.getHeader(HttpHeaders.SET_COOKIE))
                .contains(MetaOAuthCallbackController.STATE_COOKIE_PREFIX + FIRST_STATE + "=", "Max-Age=0")
                .doesNotContain(SECOND_STATE);

        var second = mvc.perform(get(MetaOAuthCallbackController.CALLBACK_PATH)
                        .param("state", SECOND_STATE).param("code", "second-code")
                        .cookie(stateCookie(SECOND_STATE)))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("https://app.example.com/meta/result?status=success&workspace_id=2&connection_id=4"))
                .andReturn().getResponse();
        assertThat(second.getHeader(HttpHeaders.SET_COOKIE))
                .contains(MetaOAuthCallbackController.STATE_COOKIE_PREFIX + SECOND_STATE + "=", "Max-Age=0")
                .doesNotContain(FIRST_STATE);
        verify(business).completeMetaAuthorization(FIRST_STATE, FIRST_STATE, "first-code", null);
        verify(business).completeMetaAuthorization(SECOND_STATE, SECOND_STATE, "second-code", null);
    }

    @Test
    void malformedStateDoesNotReadOrDeleteAnyCookie() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new MetaOAuthCallbackController(business, properties)).build();
        mvc.perform(get(MetaOAuthCallbackController.CALLBACK_PATH)
                        .param("state", "invalid-state").param("code", "private-code")
                        .cookie(stateCookie(FIRST_STATE)))
                .andExpect(status().isFound())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
        verifyNoInteractions(business);
    }

    @Test
    void missingStateDoesNotDeleteAnExistingFlowCookie() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new MetaOAuthCallbackController(business, properties)).build();
        mvc.perform(get(MetaOAuthCallbackController.CALLBACK_PATH)
                        .cookie(stateCookie(FIRST_STATE)))
                .andExpect(status().isFound())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
        verifyNoInteractions(business);
    }

    @Test
    void callbackReplacesConfiguredResultParametersAndRemovesStaleSuccessIdentityOnError() throws Exception {
        properties.setFrontendRedirectUri("https://app.example.com/meta/result?status=success&workspace_id=999&connection_id=999&error_code=old&tab=connections");
        when(business.completeMetaAuthorization(FIRST_STATE, null, null, "access_denied"))
                .thenThrow(new ApiException(ApiCode.BAD_REQUEST, "denied"));
        var mvc = MockMvcBuilders.standaloneSetup(new MetaOAuthCallbackController(business, properties)).build();
        mvc.perform(get(MetaOAuthCallbackController.CALLBACK_PATH)
                        .param("state", FIRST_STATE).param("error", "access_denied"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("https://app.example.com/meta/result?tab=connections&status=error&error_code=" + ApiCode.BAD_REQUEST.getCode()));
    }

    private Cookie stateCookie(String state) {
        return new Cookie(MetaOAuthCallbackController.STATE_COOKIE_PREFIX + state, state);
    }

    private MetaProperties properties() {
        var value = new MetaProperties();
        value.setAppId("app-id");
        value.setAppSecret("app-secret");
        value.setRedirectUri("https://api.example.com" + MetaOAuthCallbackController.CALLBACK_PATH);
        value.setFrontendRedirectUri("https://app.example.com/meta/result");
        return value;
    }
}
