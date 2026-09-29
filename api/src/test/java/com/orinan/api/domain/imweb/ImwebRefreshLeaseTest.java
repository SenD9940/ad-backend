package com.orinan.api.domain.imweb;
import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.imweb.service.ImwebRefreshLease;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class ImwebRefreshLeaseTest {
    RedisTemplate<String,String> redis=mock(RedisTemplate.class);ValueOperations<String,String> values=mock(ValueOperations.class);
    ImwebRefreshLease service=new ImwebRefreshLease(redis);
    @BeforeEach void setup() {when(redis.opsForValue()).thenReturn(values);}
    @Test void atomicallyGrantsOneRefreshCallerPerConnection() {
        when(values.setIfAbsent(eq("oauth:imweb:refresh:3"),anyString(),eq(Duration.ofMinutes(3)))).thenReturn(true,false);
        assertThat(service.acquire(3)).isNotBlank();assertThatThrownBy(()->service.acquire(3)).isInstanceOf(ApiException.class);
    }
    @Test void expiredOrReplacedLeaseCannotPersistRotatedCredentials() {
        when(values.get("oauth:imweb:refresh:3")).thenReturn("other-lease");
        assertThatThrownBy(()->service.requireOwned(3,"expected-lease")).isInstanceOf(ApiException.class);
    }
}
