package com.orinan.api.interceptor;

import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.token.business.TokenBusiness;
import com.orinan.api.domain.token.exception.TokenErrorCode;
import com.orinan.api.domain.token.helper.AuthorizationTokens;
import com.orinan.api.domain.user.exception.UserErrorCode;
import com.orinan.api.domain.user.service.UserService;
import com.orinan.db.user.enums.UserStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler;

import java.util.Objects;

@RequiredArgsConstructor
@Component
@Slf4j
public class AuthorizationInterceptor implements HandlerInterceptor {

    private final TokenBusiness tokenBusiness;
    private final UserService userService;
    private final RedisTemplate<String, String> redisTemplate;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {

        if(HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }

        if(handler instanceof ResourceHttpRequestHandler) {
            return true;
        }

        String accessToken = AuthorizationTokens.extract(request.getHeader("Authorization"));
        var userId = tokenBusiness.validateAccessToken(accessToken);
        String redisKey = "blacklist:" + accessToken;
        Boolean isBlacklisted = redisTemplate.hasKey(redisKey);
        if(Boolean.TRUE.equals(isBlacklisted)) {
            throw new ApiException(TokenErrorCode.EXPIRED_TOKEN);
        }
        if(userId == null) {
            throw new ApiException(TokenErrorCode.TOKEN_EXCEPTION, "토큰에 이상이 있습니다");
        }
        var user = userService.findByIdAndStatusWithThrow(userId, UserStatus.REGISTERED);
        if(user == null) {
            throw new ApiException(UserErrorCode.USER_NOT_FOUND, "존재하지 않거나 권한이 없는 유저 입니다");
        }
        var requestContext = Objects.requireNonNull(RequestContextHolder.getRequestAttributes());
        requestContext.setAttribute("userId", userId, RequestAttributes.SCOPE_REQUEST);
        return true;
    }
}
