package com.orinan.api.domain.imweb.client;

import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.exception.ApiException;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.util.Set;

@Getter @Setter @Component @ConfigurationProperties(prefix = "app.imweb")
public class ImwebProperties {
    public static final String SCOPES = "site-info:read site-info:write product:read product:write order:read";
    private boolean enabled;
    private String clientId;
    private String clientSecret;
    private String redirectUri;
    private String frontendRedirectUri;
    public String getScopes() { return SCOPES; }
    public boolean isConfigured() {
        return enabled && text(clientId) && text(clientSecret)
                && redirect(redirectUri, "/open-api/platform-connections/imweb/callback")
                && redirect(frontendRedirectUri, "/settings/integrations/imweb/callback");
    }
    public void validate() { if (!isConfigured()) throw new ApiException(ApiCode.BAD_REQUEST,
            "아임웹 앱의 OAuth 연결 설정이 필요합니다. 관리자에게 문의해 주세요."); }
    private boolean text(String s) { return s != null && !s.isBlank(); }
    private boolean redirect(String value, String path) {
        if (!text(value)) return false;
        try {
            URI u = URI.create(value);
            return u.getHost() != null && u.getUserInfo() == null && u.getFragment() == null && u.getQuery() == null
                    && path.equals(u.getPath()) && ("https".equals(u.getScheme()) || "http".equals(u.getScheme())
                    && Set.of("localhost", "127.0.0.1", "[::1]", "::1").contains(u.getHost()));
        } catch (IllegalArgumentException e) { return false; }
    }
}
