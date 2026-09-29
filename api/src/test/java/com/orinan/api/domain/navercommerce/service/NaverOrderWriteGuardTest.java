package com.orinan.api.domain.navercommerce.service;

import com.orinan.api.common.exception.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
class NaverOrderWriteGuardTest {
    private final RedisTemplate<String, String> redis = mock(RedisTemplate.class);
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final NaverOrderWriteGuard guard = new NaverOrderWriteGuard(redis);
    private final Map<String, String> state = new HashMap<>();
    @BeforeEach void setup() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenAnswer(call ->
                state.putIfAbsent(call.getArgument(0), call.getArgument(1)) == null);
        when(values.get(anyString())).thenAnswer(call -> state.get(call.getArgument(0)));
    }
    @Test void sameSellerOrderCannotBeWrittenConcurrentlyEvenWithDifferentRequestIds() {
        var lease = guard.acquire("same-seller", "123", UUID.randomUUID().toString());
        guard.requireOwned(lease);
        assertThatThrownBy(() -> guard.acquire("same-seller", "123", UUID.randomUUID().toString()))
                .isInstanceOf(ApiException.class).hasMessageContaining("진행 중");
        assertThat(state.keySet()).allMatch(key -> !key.contains("same-seller"));
    }
    @Test void sameRequestCannotReplayAfterFirstOrderLockWasReleased() {
        String request = UUID.randomUUID().toString();
        var lease = guard.acquire("seller", "123", request);
        state.remove(lease.key()); // Model an already released/expired order lease while the dedupe record remains.
        assertThatThrownBy(() -> guard.acquire("seller", "123", request))
                .isInstanceOf(ApiException.class).hasMessageContaining("이미 제출");
    }
    @Test void expiredOrReplacedLeaseStopsAWriteBeforeProviderCall() {
        var lease = guard.acquire("seller", "123", UUID.randomUUID().toString());
        state.put(lease.key(), "different-owner");
        assertThatThrownBy(() -> guard.requireOwned(lease)).isInstanceOf(ApiException.class);
    }
    @Test void differentOrdersAndSellersDoNotBlockEachOther() {
        var first = guard.acquire("seller-1", "123", UUID.randomUUID().toString());
        var second = guard.acquire("seller-1", "456", UUID.randomUUID().toString());
        var third = guard.acquire("seller-2", "123", UUID.randomUUID().toString());
        assertThat(first.key()).isNotEqualTo(second.key()).isNotEqualTo(third.key());
        guard.requireOwned(first); guard.requireOwned(second); guard.requireOwned(third);
    }
    @Test void missingRedisConfirmationDoesNotGrantWriteAccess() {
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(null);
        assertThatThrownBy(() -> guard.acquire("seller", "123", UUID.randomUUID().toString())).isInstanceOf(ApiException.class);
    }
}
