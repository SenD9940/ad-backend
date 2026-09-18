package com.orinan.api.domain.token.helper;

import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.token.exception.TokenErrorCode;
import com.orinan.api.domain.token.ifs.TokenHelperIfs;
import com.orinan.api.domain.token.model.TokenDto;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
public class TokenHelper implements TokenHelperIfs {

    @Value("${token.secret.key}")
    private String secretKey;

    @Value("${token.access-token.plus-hour}")
    private Long accessTokenPlusHour;

    @Value("${token.refresh-token.plus-hour}")
    private Long refreshTokenPlusHour;

    @Override
    public TokenDto issueAccessToken(Map<String, Object> data) {
        return getTokenDto(data, accessTokenPlusHour);
    }

    @Override
    public TokenDto issueRefreshToken() {
        return getTokenDto(new HashMap<>(), refreshTokenPlusHour);  // userId는 포함하지 않음
    }

    private TokenDto getTokenDto(Map<String, Object> data, Long tokenPlusHour) {
        LocalDateTime expiredLocalDateTime = LocalDateTime.now().plusHours(tokenPlusHour);


        Date tokenExpiredAt = Date.from(
                expiredLocalDateTime.atZone(ZoneId.systemDefault()).toInstant()
        );

        SecretKey key = Keys.hmacShaKeyFor(secretKey.getBytes(StandardCharsets.UTF_8));

        String token = Jwts.builder()
                .claims(data)
                .id(UUID.randomUUID().toString())
                .issuedAt(new Date())
                .expiration(tokenExpiredAt)
                .signWith(key, Jwts.SIG.HS256)
                .compact();

        return TokenDto.builder()
                .token(token)
                .expiredAt(expiredLocalDateTime)
                .build();
    }

    @Override
    public Map<String, Object> validationTokenWithThrow(String token) {
        SecretKey key = Keys.hmacShaKeyFor(secretKey.getBytes(StandardCharsets.UTF_8));
        JwtParser parser = Jwts.parser()
                .verifyWith(key)
                .build();

        try {
            var result = parser.parseSignedClaims(token);
            return new HashMap<>(result.getPayload());
        } catch (Exception e) {
            if (e instanceof SignatureException) {
                throw new ApiException(TokenErrorCode.INVALID_TOKEN, e);
            } else if (e instanceof ExpiredJwtException) {
                throw new ApiException(TokenErrorCode.EXPIRED_TOKEN, e);
            } else {
                throw new ApiException(TokenErrorCode.TOKEN_EXCEPTION, e);
            }
        }
    }

    public long getExpireSecondsFromToken(String token) {
        SecretKey key = Keys.hmacShaKeyFor(secretKey.getBytes(StandardCharsets.UTF_8));
        JwtParser parser = Jwts.parser()
                .verifyWith(key)
                .build();

        Claims claims = parser.parseSignedClaims(token).getPayload();

        Date expiration = claims.getExpiration(); // 만료시간 (Date)
        long nowMillis = System.currentTimeMillis();

        long expireMillis = expiration.getTime() - nowMillis;
        long expireSeconds = expireMillis / 1000;

        return expireSeconds > 0 ? expireSeconds : 0;
    }
}
