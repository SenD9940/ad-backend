package com.orinan.api.domain.navercommerce.service;

import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.token.business.TokenBusiness;
import com.orinan.api.domain.token.exception.TokenErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.RedisTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
class NaverOrderSessionServiceTest {
    private final ObjectProvider<HttpServletRequest> provider = mock(ObjectProvider.class);
    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final TokenBusiness tokens = mock(TokenBusiness.class);
    private final RedisTemplate<String, String> redis = mock(RedisTemplate.class);
    private final NaverOrderSessionService service = new NaverOrderSessionService(provider, tokens, redis);
    @BeforeEach void setup() {
        when(provider.getObject()).thenReturn(request); when(request.getHeader("Authorization")).thenReturn("Bearer synthetic-token");
        when(tokens.validateAccessToken("synthetic-token")).thenReturn(2L);
    }
    @Test void validatesOriginalTokenAndBlacklistAgainstExpectedUser() {
        service.requireCurrent(2L); verify(tokens).validateAccessToken("synthetic-token"); verify(redis).hasKey("blacklist:synthetic-token");
        assertThatThrownBy(() -> service.requireCurrent(3L)).isInstanceOf(ApiException.class);
    }
    @Test void logoutOrRevokedAuthenticationPreventsLaterWrite() {
        when(redis.hasKey("blacklist:synthetic-token")).thenReturn(true);
        assertThatThrownBy(() -> service.requireCurrent(2L)).isInstanceOf(ApiException.class);
        doThrow(new ApiException(TokenErrorCode.INVALID_TOKEN)).when(tokens).validateAccessToken("synthetic-token");
        assertThatThrownBy(() -> service.requireCurrent(2L)).isInstanceOf(ApiException.class);
    }
    @Test void absentSessionCannotUseMembershipAlone() {
        when(request.getHeader("Authorization")).thenReturn(null);
        assertThatThrownBy(() -> service.requireCurrent(2L)).isInstanceOf(ApiException.class);
        verifyNoInteractions(tokens, redis);
    }
}
