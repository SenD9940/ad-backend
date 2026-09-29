package com.orinan.api.domain.imweb;
import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.imweb.service.ImwebOAuthStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImwebOAuthStateServiceTest {
    RedisTemplate<String,String> redis = mock(RedisTemplate.class);
    ValueOperations<String,String> values = mock(ValueOperations.class);
    ImwebOAuthStateService service = new ImwebOAuthStateService(redis);
    String state = "a".repeat(43);
    @BeforeEach void setup() { when(redis.opsForValue()).thenReturn(values); }
    @Test void issuesRandomBrowserBoundStateWithWorkspaceOwnerAndSite() {
        String issued = service.issue(1L, 2L, "Stest123");
        assertThat(issued).matches("[A-Za-z0-9_-]{43}");
        verify(values).set("oauth:imweb:state:" + issued, "1:2:Stest123", ImwebOAuthStateService.VALIDITY);
    }
    @Test void consumesAtomicallyAndRejectsReplay() {
        when(values.getAndDelete("oauth:imweb:state:" + state)).thenReturn("1:2:Stest123").thenReturn(null);
        assertThat(service.consume(state, state)).isEqualTo(new ImwebOAuthStateService.Owner(1,2,"Stest123"));
        assertThatThrownBy(() -> service.consume(state,state)).isInstanceOf(ApiException.class);
    }
    @Test void missingOrWrongBrowserCookieDoesNotConsumeValidState() {
        assertThatThrownBy(() -> service.consume(state,null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.consume(state,"b".repeat(43))).isInstanceOf(ApiException.class);
        verifyNoInteractions(values);
    }
    @Test void rejectsMalformedStoredData() {
        for (String stored : new String[]{"1:2", "0:2:Stest123", "1:2:not-site", "1:x:Stest123"}) {
            when(values.getAndDelete(anyString())).thenReturn(stored);
            assertThatThrownBy(() -> service.consume(state,state)).isInstanceOf(ApiException.class);
        }
    }
    @Test void neverInterpolatesUnvalidatedSiteOrStateIntoRedis() {
        assertThatThrownBy(() -> service.issue(1L,2L,"Sfoo:bar")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.consume("bad", "bad")).isInstanceOf(ApiException.class);
        verifyNoInteractions(values);
    }
}
