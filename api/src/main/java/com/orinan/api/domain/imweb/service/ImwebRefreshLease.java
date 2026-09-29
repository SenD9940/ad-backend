package com.orinan.api.domain.imweb.service;

import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.exception.ApiException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** Rotating refresh tokens must have a single caller across application instances. */
@Service
public class ImwebRefreshLease {
    private static final Duration TTL = Duration.ofMinutes(3);
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end", Long.class);
    private final RedisTemplate<String, String> redis;

    public ImwebRefreshLease(@Qualifier("redisTemplate") RedisTemplate<String, String> redis) {
        this.redis = redis;
    }

    public String acquire(long connectionId) {
        String value = UUID.randomUUID().toString();
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key(connectionId), value, TTL)))
            throw new ApiException(ApiCode.BAD_REQUEST, "아임웹 연결 정보를 갱신하고 있습니다. 잠시 후 다시 조회해 주세요.");
        return value;
    }
    public void requireOwned(long connectionId, String lease) {
        if (!lease.equals(redis.opsForValue().get(key(connectionId))))
            throw new ApiException(ApiCode.BAD_REQUEST, "아임웹 인증 갱신 결과를 확인할 수 없습니다. 다시 연결해 주세요.");
    }
    public void release(long connectionId, String lease) { redis.execute(RELEASE, List.of(key(connectionId)), lease); }
    private String key(long connectionId) { return "oauth:imweb:refresh:" + connectionId; }
}
