package com.orinan.api.domain.navercommerce.service;

import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.exception.ApiException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** Cross-instance serialization and request deduplication; no order payload or recipient data is stored. */
@Service
public class NaverOrderWriteGuard {
    private final RedisTemplate<String, String> redis;
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "if redis.call('get',KEYS[1]) == ARGV[1] then return redis.call('del',KEYS[1]) else return 0 end", Long.class);
    private static final DefaultRedisScript<Long> HOLD = new DefaultRedisScript<>(
            "if redis.call('get',KEYS[1]) == ARGV[1] then return redis.call('pexpire',KEYS[1],600000) else return 0 end", Long.class);

    public NaverOrderWriteGuard(@Qualifier("redisTemplate") RedisTemplate<String, String> redis) {
        this.redis = redis;
    }

    public Lease acquire(String accountUid, String productOrderId, String requestId) {
        String scope = digest(accountUid + ":" + productOrderId);
        var lease = new Lease("naver:orders:lock:" + scope, UUID.randomUUID().toString());
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(lease.key(), lease.owner(), Duration.ofMinutes(3))))
            throw bad("이 상품주문의 처리가 진행 중이거나 결과 확인이 필요합니다. 스마트스토어센터에서 상태를 확인한 뒤 잠시 후 다시 조회해 주세요.");
        try {
            if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent("naver:orders:request:" + scope + ":" + requestId,
                    "used", Duration.ofDays(1)))) throw bad("이미 제출한 요청입니다. 주문 상태를 다시 조회해 주세요.");
            return lease;
        } catch (RuntimeException failure) { release(lease); throw failure; }
    }
    public void requireOwned(Lease lease) {
        if (!lease.owner().equals(redis.opsForValue().get(lease.key()))) throw bad("주문 처리 확인 시간이 만료되었습니다. 주문을 다시 조회해 주세요.");
    }
    public void release(Lease lease) { redis.execute(RELEASE, List.of(lease.key()), lease.owner()); }
    public void hold(Lease lease) { redis.execute(HOLD, List.of(lease.key()), lease.owner()); }
    private String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private ApiException bad(String message) { return new ApiException(ApiCode.BAD_REQUEST, message); }
    public record Lease(String key, String owner) { @Override public String toString() { return "OrderWriteLease[REDACTED]"; } }
}
