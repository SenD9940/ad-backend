package com.orinan.api.domain.platformconnection.naver.selftest;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** These credentials are server-only and must never be returned by the capability API. */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.naver-commerce")
public class NaverSelfTestProperties {
    private String appId;
    private String appSecret;
    private SelfTest selfTest = new SelfTest();

    @Getter
    @Setter
    public static class SelfTest {
        private boolean enabled;
        private Long workspaceId;
    }
}
