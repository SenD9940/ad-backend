package com.orinan.api.domain.imweb.service;

import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.exception.ApiException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

@Service
public class ImwebOAuthStateService {
    public static final Duration VALIDITY = Duration.ofMinutes(10);
    private static final String PREFIX = "oauth:imweb:state:";
    private static final SecureRandom RANDOM = new SecureRandom();
    private final RedisTemplate<String, String> redis;

    public ImwebOAuthStateService(@Qualifier("redisTemplate") RedisTemplate<String, String> redis) {
        this.redis = redis;
    }

    public String issue(Long workspaceId, Long userId, String siteCode) {
        if (!validSite(siteCode)) throw invalid();
        byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        redis.opsForValue().set(PREFIX + state, workspaceId + ":" + userId + ":" + siteCode, VALIDITY);
        return state;
    }
    public Owner consume(String state, String browserState) {
        if (!validState(state) || browserState == null || !MessageDigest.isEqual(
                state.getBytes(StandardCharsets.US_ASCII), browserState.getBytes(StandardCharsets.US_ASCII))) throw invalid();
        String value = redis.opsForValue().getAndDelete(PREFIX + state);
        if (value == null) throw invalid();
        try {
            String[] parts = value.split(":", -1);
            if (parts.length != 3 || !validSite(parts[2])) throw invalid();
            long workspace = Long.parseLong(parts[0]), user = Long.parseLong(parts[1]);
            if (workspace < 1 || user < 1) throw invalid();
            return new Owner(workspace, user, parts[2]);
        } catch (NumberFormatException e) { throw invalid(); }
    }
    public static boolean validSite(String s) { return s != null && s.matches("S[A-Za-z0-9]{5,99}"); }
    public static boolean validUnit(String s) { return s != null && s.matches("u[A-Za-z0-9]{5,99}"); }
    public static boolean validState(String s) { return s != null && s.matches("[A-Za-z0-9_-]{43}"); }
    private ApiException invalid() { return new ApiException(ApiCode.BAD_REQUEST,
            "유효하지 않거나 만료된 아임웹 연결 요청입니다. 다시 연결해 주세요."); }
    public record Owner(long workspaceId, long userId, String siteCode) {}
}
