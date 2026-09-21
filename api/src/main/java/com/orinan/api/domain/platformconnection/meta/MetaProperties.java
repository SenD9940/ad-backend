package com.orinan.api.domain.platformconnection.meta;

import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.exception.ApiException;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.Set;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.meta")
public class MetaProperties {

    private String appId;
    private String appSecret;
    private String redirectUri;
    private String frontendRedirectUri;
    private String apiVersion = "v26.0";

    // Validate when the integration is used, so other APIs can run without Meta credentials.
    public void validate() {
        if (!StringUtils.hasText(appId) || !StringUtils.hasText(appSecret)
                || apiVersion == null || !apiVersion.matches("v[0-9]{1,3}\\.[0-9]{1,2}")
                || !isValidRedirect(redirectUri) || !isValidRedirect(frontendRedirectUri)) {
            throw new ApiException(ApiCode.SERVER_ERROR, "Meta 연동 설정을 확인해 주세요.");
        }
    }

    private boolean isValidRedirect(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        try {
            URI uri = URI.create(value);
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) {
                return false;
            }
            return "https".equalsIgnoreCase(uri.getScheme())
                    || ("http".equalsIgnoreCase(uri.getScheme())
                    && Set.of("localhost", "127.0.0.1", "[::1]", "::1").contains(uri.getHost()));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
