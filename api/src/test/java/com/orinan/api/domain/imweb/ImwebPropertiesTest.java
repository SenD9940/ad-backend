package com.orinan.api.domain.imweb;
import com.orinan.api.domain.imweb.client.ImwebProperties;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class ImwebPropertiesTest {
    static ImwebProperties configured() {
        var p=new ImwebProperties(); p.setEnabled(true); p.setClientId("test-id"); p.setClientSecret("test-secret");
        p.setRedirectUri("http://localhost:8480/open-api/platform-connections/imweb/callback");
        p.setFrontendRedirectUri("http://localhost:3400/settings/integrations/imweb/callback"); return p;
    }
    @Test void integrationIsDisabledWithoutExplicitConfiguration() { assertThat(new ImwebProperties().isConfigured()).isFalse(); }
    @Test void fixedCallbackPathsAndLocalHttpWork() { assertThat(configured().isConfigured()).isTrue(); }
    @Test void rejectsUnsafeRedirects() {
        for(String url : new String[]{"http://example.com/open-api/platform-connections/imweb/callback", "https://example.com/elsewhere", "https://user:pass@example.com/open-api/platform-connections/imweb/callback", "https://example.com/open-api/platform-connections/imweb/callback?redirect=evil"}) {
            var p=configured();p.setRedirectUri(url);assertThat(p.isConfigured()).isFalse();
        }
    }
    @Test void supportsProductionHttpsAndCannotOverrideScopes() {
        var p=configured(); p.setRedirectUri("https://api.example.com/open-api/platform-connections/imweb/callback");
        p.setFrontendRedirectUri("https://app.example.com/settings/integrations/imweb/callback");
        assertThat(p.isConfigured()).isTrue(); assertThat(p.getScopes()).contains("site-info:write", "product:write", "order:read");
    }
}
