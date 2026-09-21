package com.orinan.api.domain.platformconnection;

import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.platformconnection.service.MetaOAuthStateService;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MetaOAuthStateTest {

    @SuppressWarnings("unchecked")
    private final RedisTemplate<String, String> redis = mock(RedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final MetaOAuthStateService service = new MetaOAuthStateService(redis);

    @Test
    void stateIsBoundToWorkspaceOwnerAndConsumedOnlyOnce() {
        when(redis.opsForValue()).thenReturn(values);
        String state = service.issue(10L, 20L);
        assertThat(state).matches("[A-Za-z0-9_-]{43}");
        verify(values).set("oauth:meta:state:" + state, "10:20", MetaOAuthStateService.VALIDITY);
        when(values.getAndDelete("oauth:meta:state:" + state)).thenReturn("10:20", (String) null);

        assertThat(service.consume(state, state)).isEqualTo(new MetaOAuthStateService.OAuthOwner(10L, 20L));
        assertThatThrownBy(() -> service.consume(state, state)).isInstanceOf(ApiException.class);
    }

    @Test
    void callbackFromAnotherBrowserCannotConsumeState() {
        String state = "s".repeat(43);
        assertThatThrownBy(() -> service.consume(state, null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.consume(state, "x".repeat(43))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.consume("invalid", "invalid")).isInstanceOf(ApiException.class);
        verifyNoInteractions(redis);
    }

    @Test
    void expiredStateCannotBeUsed() {
        when(redis.opsForValue()).thenReturn(values);
        assertThatThrownBy(() -> service.consume("s".repeat(43), "s".repeat(43)))
                .isInstanceOf(ApiException.class).hasMessageContaining("만료");
    }
}
