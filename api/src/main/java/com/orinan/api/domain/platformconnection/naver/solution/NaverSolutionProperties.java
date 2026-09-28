package com.orinan.api.domain.platformconnection.naver.solution;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.util.Set;

/** Server-only registration settings. No guessed authorization endpoint is supplied. */
@Getter
@Setter
@ConfigurationProperties(prefix = "naver.solution")
public class NaverSolutionProperties {
    private boolean enabled;
    private boolean providerContractVerified;
    private String applicationRef;
    private String solutionId;
    private String clientId;
    private String clientSecret;
    private String jwtPublicKey;
    private String publicBaseUrl;
    private String frontendBaseUrl;
    private String marketplaceUrl;
    private String authorizationUrlTemplate;
    private String callbackStateParameter;
    private String callbackProofParameter;
    private String marketplaceTokenParameter;
    private String webhookHeaderName;
    private String webhookKey;
    private long credentialVersion = 1;

    public boolean ready() {
        return enabled && providerContractVerified && text(applicationRef) && text(solutionId)
                && text(clientId) && text(clientSecret) && text(jwtPublicKey)
                && publicOrigin(publicBaseUrl) && publicOrigin(frontendBaseUrl)
                && publicBaseUrl.equals(frontendBaseUrl)
                && naverUrl(marketplaceUrl) && validAuthorizationTemplate()
                && parameter(callbackStateParameter) && parameter(callbackProofParameter)
                && !callbackStateParameter.equals(callbackProofParameter)
                && parameter(marketplaceTokenParameter)
                && parameter(webhookHeaderName) && text(webhookKey) && webhookKey.length() >= 32
                && credentialVersion > 0;
    }

    public String callbackUrl() {
        return publicBaseUrl + "/open-api/integrations/naver/callback";
    }

    private boolean validAuthorizationTemplate() {
        if (!text(authorizationUrlTemplate)
                || !authorizationUrlTemplate.contains("{state}")
                || !authorizationUrlTemplate.contains("{callback}")) return false;
        String replaced = authorizationUrlTemplate.replace("{state}", "request-state")
                .replace("{callback}", "https%3A%2F%2Fexample.invalid%2Fcallback")
                .replace("{solutionId}", "solution-id");
        if (replaced.contains("{") || replaced.contains("}")) return false;
        try {
            URI uri = URI.create(replaced);
            // State and callback must be query values, never path/authority substitutions.
            return naverUrl(replaced) && uri.getRawQuery() != null
                    && uri.getRawQuery().contains("request-state")
                    && uri.getRawQuery().contains("https%3A%2F%2Fexample.invalid%2Fcallback");
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    static boolean naverUrl(String value) {
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            return "https".equals(uri.getScheme()) && host != null && host.endsWith(".naver.com")
                    && uri.getRawUserInfo() == null && uri.getRawFragment() == null
                    && (uri.getPort() == -1 || uri.getPort() == 443);
        } catch (RuntimeException exception) { return false; }
    }

    private static boolean publicOrigin(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equals(uri.getScheme()) && uri.getHost() != null
                    && uri.getRawUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null
                    && (uri.getRawPath() == null || uri.getRawPath().isEmpty())
                    && !Set.of("localhost", "127.0.0.1", "::1").contains(uri.getHost());
        } catch (RuntimeException exception) { return false; }
    }

    private static boolean text(String value) { return value != null && !value.isBlank(); }
    private static boolean parameter(String value) {
        return value != null && value.matches("[A-Za-z][A-Za-z0-9_-]{0,99}");
    }
}
