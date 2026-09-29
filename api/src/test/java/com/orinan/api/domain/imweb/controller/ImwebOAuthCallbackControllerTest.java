package com.orinan.api.domain.imweb.controller;
import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.imweb.business.ImwebConnectionBusiness;
import com.orinan.api.domain.imweb.client.ImwebProperties;
import com.orinan.api.domain.platformconnection.controller.model.PlatformConnectionResponse;
import com.orinan.db.platformconnection.enums.ProviderType;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import java.time.Duration;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImwebOAuthCallbackControllerTest {
    ImwebProperties properties=properties(); ImwebConnectionBusiness business=mock(ImwebConnectionBusiness.class);
    ImwebOAuthCallbackController controller=new ImwebOAuthCallbackController(business,properties);
    String state="a".repeat(43);
    static ImwebProperties properties() {var p=new ImwebProperties();p.setEnabled(true);p.setClientId("client");p.setClientSecret("secret");
        p.setRedirectUri("https://api.example.com/open-api/platform-connections/imweb/callback");
        p.setFrontendRedirectUri("https://app.example.com/settings/integrations/imweb/callback");return p;}
    @Test void cookieIsHttpOnlySecureLaxAndScopedToCallback() {
        var cookie=ImwebOAuthCallbackController.cookie(properties,state,Duration.ofMinutes(10));
        assertThat(cookie.isHttpOnly()).isTrue();assertThat(cookie.isSecure()).isTrue();assertThat(cookie.getSameSite()).isEqualTo("Lax");
        assertThat(cookie.getPath()).isEqualTo(ImwebOAuthCallbackController.CALLBACK_PATH);assertThat(cookie.getMaxAge()).isEqualTo(Duration.ofMinutes(10));
    }
    @Test void callbackReadsPerAttemptBrowserCookieAndRedirectsOnlySafeIdentifiers() {
        var request=new MockHttpServletRequest();request.setCookies(new Cookie("imweb_oauth_state_"+state,state));
        when(business.complete(state,state,"secret-code",null)).thenReturn(new PlatformConnectionResponse(3L,1L,ProviderType.IMWEB,"Stest123","Shop",false,null,List.of(),null,null));
        var response=controller.callback(state,"secret-code",null,request);
        assertThat(response.getStatusCode().value()).isEqualTo(302);
        assertThat(response.getHeaders().getLocation().toString()).isEqualTo("https://app.example.com/settings/integrations/imweb/callback?status=success&workspace_id=1&connection_id=3");
        assertThat(response.getHeaders().getFirst(HttpHeaders.SET_COOKIE)).contains("Max-Age=0","HttpOnly","Secure");
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
    }
    @Test void rawProviderErrorsAndCredentialsNeverReachRedirect() {
        when(business.complete(state,null,"secret-code",null)).thenThrow(new ApiException(ApiCode.SERVER_ERROR,"sensitive-upstream"));
        var response=controller.callback(state,"secret-code",null,new MockHttpServletRequest());
        assertThat(response.getHeaders().getLocation().toString()).contains("status=error").doesNotContain("sensitive-upstream","secret-code",state);
    }
    @Test void malformedStateCannotCreateCookieHeader() {
        when(business.complete("bad",null,null,null)).thenThrow(new ApiException(ApiCode.BAD_REQUEST));
        var response=controller.callback("bad",null,null,new MockHttpServletRequest());
        assertThat(response.getHeaders().getFirst(HttpHeaders.SET_COOKIE)).isNull();
    }
}
