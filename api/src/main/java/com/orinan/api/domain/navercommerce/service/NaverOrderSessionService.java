package com.orinan.api.domain.navercommerce.service;

import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.token.business.TokenBusiness;
import com.orinan.api.domain.token.exception.TokenErrorCode;
import com.orinan.api.domain.token.helper.AuthorizationTokens;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.Objects;

/** Recheck the original service session after slow provider reads, in a fresh persistence context. */
@Service
public class NaverOrderSessionService {
    private final ObjectProvider<HttpServletRequest> request;
    private final TokenBusiness tokens;
    private final RedisTemplate<String, String> redis;

    public NaverOrderSessionService(ObjectProvider<HttpServletRequest> request, TokenBusiness tokens,
                                   @Qualifier("redisTemplate") RedisTemplate<String, String> redis) {
        this.request = request;
        this.tokens = tokens;
        this.redis = redis;
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public void requireCurrent(Long expectedUserId) {
        String token = AuthorizationTokens.extract(request.getObject().getHeader("Authorization"));
        Long actualUserId = tokens.validateAccessToken(token); // Checks REGISTERED and current authVersion.
        if (!Objects.equals(expectedUserId, actualUserId) || Boolean.TRUE.equals(redis.hasKey("blacklist:" + token)))
            throw new ApiException(TokenErrorCode.INVALID_TOKEN);
    }
}
