package com.orinan.api.domain.platformconnection.service;

import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.exception.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

@Service
@RequiredArgsConstructor
public class MetaOAuthStateService {

    public static final Duration VALIDITY = Duration.ofMinutes(10);
    private static final String PREFIX = "oauth:meta:state:";
    private static final SecureRandom RANDOM = new SecureRandom();
    private final RedisTemplate<String, String> redisTemplate;

    public String issue(Long workspaceId, Long userId) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        redisTemplate.opsForValue().set(PREFIX + state, workspaceId + ":" + userId, VALIDITY);
        return state;
    }

    public OAuthOwner consume(String state, String browserState) {
        if (state == null || !state.matches("[A-Za-z0-9_-]{43}") || browserState == null
                || !MessageDigest.isEqual(state.getBytes(StandardCharsets.US_ASCII),
                browserState.getBytes(StandardCharsets.US_ASCII))) {
            throw invalidState();
        }
        // GETDEL prevents two callbacks from accepting the same authorization.
        String value = redisTemplate.opsForValue().getAndDelete(PREFIX + state);
        if (value == null) {
            throw invalidState();
        }
        try {
            String[] ids = value.split(":", -1);
            if (ids.length != 2) {
                throw invalidState();
            }
            long workspaceId = Long.parseLong(ids[0]);
            long userId = Long.parseLong(ids[1]);
            if (workspaceId <= 0 || userId <= 0) {
                throw invalidState();
            }
            return new OAuthOwner(workspaceId, userId);
        } catch (NumberFormatException exception) {
            throw invalidState();
        }
    }

    private ApiException invalidState() {
        return new ApiException(ApiCode.BAD_REQUEST, "유효하지 않거나 만료된 Meta 연결 요청입니다. 다시 연결해 주세요.");
    }

    public record OAuthOwner(Long workspaceId, Long userId) {
    }
}
