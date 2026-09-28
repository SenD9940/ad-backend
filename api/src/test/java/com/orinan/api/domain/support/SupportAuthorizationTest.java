package com.orinan.api.domain.support;

import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.support.exception.SupportErrorCode;
import com.orinan.api.domain.support.model.SupportContext;
import com.orinan.api.domain.token.business.TokenBusiness;
import com.orinan.api.domain.user.service.UserService;
import com.orinan.api.interceptor.AuthorizationInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SupportAuthorizationTest {
    private final TokenBusiness tokens = mock(TokenBusiness.class);
    private final UserService users = mock(UserService.class);
    @SuppressWarnings("unchecked") private final RedisTemplate<String, String> redis = mock(RedisTemplate.class);
    private final AuthorizationInterceptor auth = new AuthorizationInterceptor(tokens, users, redis);

    @Test void validatedScopedIdentityBypassesOrdinaryTokenProcessing() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/workspaces/10");
        var context = new SupportContext(1, 2, 10, 3, 4, "READ_ONLY", LocalDateTime.now().plusMinutes(30));
        request.setAttribute(SupportContext.REQUEST_ATTRIBUTE, context);
        request.setAttribute("userId", context.customerUserId());
        request.addHeader("X-Support-Token", "a".repeat(43));
        assertThat(auth.preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
        assertThat(request.getAttribute("userId")).isEqualTo(4L);
        verifyNoInteractions(tokens, users, redis);
    }

    @Test void supportHeaderWithoutValidatedContextNeverFallsBackToAnOrdinaryToken() {
        var request = new MockHttpServletRequest("GET", "/api/workspaces/10");
        request.addHeader("X-Support-Token", "a".repeat(43));
        request.addHeader("Authorization", "Bearer ordinary-customer-token");
        assertThatThrownBy(() -> auth.preHandle(request, new MockHttpServletResponse(), new Object()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCodeIfs()).isEqualTo(SupportErrorCode.INVALID_SESSION));
        verifyNoInteractions(tokens, users, redis);
    }
}
