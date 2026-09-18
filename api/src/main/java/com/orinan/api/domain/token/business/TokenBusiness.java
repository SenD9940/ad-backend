package com.orinan.api.domain.token.business;

import com.orinan.api.annotation.Business;
import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.token.controller.model.TokenResponse;
import com.orinan.api.domain.token.converter.TokenConverter;
import com.orinan.api.domain.token.exception.TokenErrorCode;
import com.orinan.api.domain.token.helper.TokenHelper;
import com.orinan.api.domain.token.model.TokenDto;
import com.orinan.api.domain.token.service.TokenService;
import com.orinan.api.domain.user.service.UserService;
import com.orinan.db.token.TokenEntity;
import com.orinan.db.user.UserEntity;
import com.orinan.db.user.enums.UserStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

@Slf4j
@Business
@RequiredArgsConstructor
public class TokenBusiness {

    private final RedisTemplate<String, String> redisTemplate;
    private final TokenService tokenService;
    private final TokenConverter tokenConverter;
    private final TokenHelper tokenHelper;
    private final UserService userService;

    @Transactional
    public TokenResponse issueToken(UserEntity userEntity) {
        TokenDto accessTokenDto = tokenService.issueAccessToken(userEntity.getId());
        TokenDto refreshTokenDto = tokenService.issueRefreshToken(userEntity.getId());
        return tokenConverter.toResponse(accessTokenDto, refreshTokenDto);
    }

    @Transactional
    public TokenResponse refreshTokenAndIssueNewTokenWithRefreshToken(String refreshToken) {
        var tokenEntity = tokenService.validateRefreshToken(refreshToken);
        userService.findByIdAndStatusWithThrow(tokenEntity.getUserId(), UserStatus.REGISTERED);
        tokenService.expireRefreshToken(refreshToken);
        TokenDto accessTokenDto = tokenService.issueAccessToken(tokenEntity.getUserId());
        TokenDto refreshTokenDto = tokenService.issueRefreshToken(tokenEntity.getUserId());
        return tokenConverter.toResponse(accessTokenDto, refreshTokenDto);
    }

    public Long validateAccessToken(String token) {
        return tokenService.validateAccessToken(token);
    }

    @Transactional
    public Long validateRefreshToken(String refreshToken) {
        TokenEntity tokenEntity =  tokenService.validateRefreshToken(refreshToken);
        return tokenEntity.getId();
    }

    @Transactional
    public boolean expireToken(Long userId, String accessToken, String refreshToken) {
        var token = tokenService.validateRefreshToken(refreshToken);
        if (!token.getUserId().equals(userId)) {
            throw new ApiException(TokenErrorCode.INVALID_TOKEN);
        }
        blacklistToken(accessToken);
        tokenService.expireRefreshToken(refreshToken);
        return true;
    }

    public void blacklistToken(String accessToken) {
        long expireSeconds = tokenHelper.getExpireSecondsFromToken(accessToken);
        String key = "blacklist:" + accessToken;
        if (expireSeconds <= 0) {
            // 이미 만료된 토큰은 블랙리스트 저장 불필요
            return;
        }
        redisTemplate.opsForValue().set(key, "true", Duration.ofSeconds(expireSeconds));
    }

}
