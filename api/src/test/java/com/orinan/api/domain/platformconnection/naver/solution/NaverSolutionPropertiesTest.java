package com.orinan.api.domain.platformconnection.naver.solution;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class NaverSolutionPropertiesTest {
    @Test void disabledAndUnverifiedNeverEnable() {
        assertThat(new NaverSolutionProperties().ready()).isFalse();
        var p = configured();
        assertThat(p.ready()).isTrue();
        p.setProviderContractVerified(false);
        assertThat(p.ready()).isFalse();
        p.setProviderContractVerified(true);
        p.setEnabled(false);
        assertThat(p.ready()).isFalse();
    }

    @Test void rejectsUnsafeAndUnboundAuthorizationUrls() {
        for (String template : List.of("https://evil.test?state={state}&callback={callback}",
                "https://naver.com.evil.test?state={state}&callback={callback}",
                "http://auth.commerce.naver.com?state={state}&callback={callback}",
                "https://auth.commerce.naver.com?callback={callback}",
                "https://auth.commerce.naver.com/{state}?callback={callback}",
                "https://auth.commerce.naver.com?state={state}&callback={callback}#{state}",
                "https://u:p@auth.commerce.naver.com?state={state}&callback={callback}",
                "https://auth.commerce.naver.com?state={state}&callback={callback}&unknown={unknown}")) {
            var p = configured(); p.setAuthorizationUrlTemplate(template);
            assertThat(p.ready()).as(template).isFalse();
        }
    }

    @Test void requiresSameOriginHttpsCallbackAndDistinctCallbackParameters() {
        var p = configured(); p.setPublicBaseUrl("http://app.example.test");
        assertThat(p.ready()).isFalse();
        p = configured(); p.setFrontendBaseUrl("https://other.example.test");
        assertThat(p.ready()).isFalse();
        p = configured(); p.setCallbackProofParameter(p.getCallbackStateParameter());
        assertThat(p.ready()).isFalse();
        p = configured(); p.setWebhookKey("short");
        assertThat(p.ready()).isFalse();
    }

    static NaverSolutionProperties configured() {
        var p = new NaverSolutionProperties();
        p.setEnabled(true); p.setProviderContractVerified(true);
        p.setApplicationRef("test-application"); p.setSolutionId("solution-1");
        p.setClientId("client-1"); p.setClientSecret("mock-client-secret");
        p.setJwtPublicKey("test-key-replaced-by-verifier-tests");
        p.setPublicBaseUrl("https://app.example.test"); p.setFrontendBaseUrl("https://app.example.test");
        p.setMarketplaceUrl("https://solution.smartstore.naver.com/test-registration");
        // Synthetic verified registration fixture; this is not a documented Naver endpoint.
        p.setAuthorizationUrlTemplate("https://auth.commerce.naver.com/test-only?nonce={state}&redirect={callback}&solution={solutionId}");
        p.setCallbackStateParameter("request_state"); p.setCallbackProofParameter("seller_proof");
        p.setMarketplaceTokenParameter("market_proof"); p.setWebhookHeaderName("X-Solution-Event-Key");
        p.setWebhookKey("only-a-mocked-webhook-secret-with-32-characters");
        return p;
    }
}
