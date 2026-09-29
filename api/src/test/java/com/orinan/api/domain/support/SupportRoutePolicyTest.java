package com.orinan.api.domain.support;

import com.orinan.api.domain.support.model.SupportContext;
import com.orinan.api.domain.support.service.SupportRoutePolicy;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class SupportRoutePolicyTest {
    private final SupportRoutePolicy policy = new SupportRoutePolicy();
    private final SupportContext read = context("READ_ONLY");
    private final SupportContext operate = context("OPERATE");
    private final HandlerMethod handler;

    SupportRoutePolicyTest() throws Exception { handler = new HandlerMethod(this, getClass().getDeclaredMethod("endpoint")); }
    void endpoint() {}

    @Test void allowsOnlyTheApprovedWorkspace() {
        assertThat(policy.permits(request("GET", "/api/workspaces/{workspaceId}", Map.of("workspaceId", "10")), handler, read)).isTrue();
        assertThat(policy.permits(request("GET", "/api/workspaces/{workspaceId}", Map.of("workspaceId", "11")), handler, read)).isFalse();
        assertThat(policy.permits(request("GET", "/api/workspaces/{workspaceId}", Map.of("workspaceId", "010")), handler, read)).isFalse();
    }

    @Test void operationsNeedExplicitWriteConsent() {
        var patch = request("PATCH", "/api/workspaces/{workspaceId}/meta/ad-accounts/{assetId}/ads/{adId}",
                Map.of("workspaceId", "10", "assetId", "30", "adId", "1200000001"));
        assertThat(policy.permits(patch, handler, read)).isFalse();
        assertThat(policy.permits(patch, handler, operate)).isTrue();
    }

    @Test void neverGrantsPermissionsToNewOrSensitiveRoutes() {
        for (String suffix : new String[]{"/members", "/owner", "/support/tickets", "/support/offer", "/connections/meta/authorize",
                "/connections/naver", "/connections/naver/self-test", "/new-feature"}) {
            for (String method : new String[]{"GET", "POST", "PATCH", "DELETE"}) {
                assertThat(policy.permits(request(method, "/api/workspaces/{workspaceId}" + suffix,
                        Map.of("workspaceId", "10")), handler, operate)).as(method + suffix).isFalse();
            }
        }
        for (String path : new String[]{"/api/users/me", "/api/users/profile", "/api/workspaces/me",
                "/open-api/users/login", "/open-api/integrations/naver/callback", "/api/support/session/new"}) {
            assertThat(policy.permits(request("POST", path, Map.of()), handler, operate)).isFalse();
            assertThat(policy.permits(request("GET", path, Map.of()), handler, operate)).isFalse();
        }
    }

    @Test void rejectsEncodedNoncanonicalAndUnknownHandlers() {
        var request = request("GET", "/api/workspaces/{workspaceId}", Map.of("workspaceId", "10"));
        for (String uri : new String[]{"/api/workspaces/%31%30", "/api/workspaces/10/", "/api//workspaces/10",
                "/api/workspaces/10;ignored=x", "/api/workspaces/11"}) {
            request.setRequestURI(uri);
            assertThat(policy.permits(request, handler, read)).isFalse();
        }
        request.setRequestURI("/api/workspaces/10");
        assertThat(policy.permits(request, new Object(), read)).isFalse();
        request.removeAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        assertThat(policy.permits(request, handler, read)).isFalse();
    }

    @Test void permitsOwnSessionMetadataAndExitInBothModes() {
        Stream.of(read, operate).forEach(context -> {
            assertThat(policy.permits(request("GET", "/api/support/session", Map.of()), handler, context)).isTrue();
            assertThat(policy.permits(request("POST", "/api/support/session/end", Map.of()), handler, context)).isTrue();
            assertThat(policy.permits(request("DELETE", "/api/support/session", Map.of()), handler, context)).isFalse();
        });
    }

    @Test void permitsConcreteExistingProductAndAssetRoutes() {
        var variables = Map.of("workspaceId", "10", "assetId", "30");
        for (String suffix : new String[]{"/products", "/sales", "/product-creation/options", "/product-creation/notices"}) {
            assertThat(policy.permits(request("GET", "/api/workspaces/{workspaceId}/naver/stores/{assetId}" + suffix,
                    variables), handler, read)).isTrue();
        }
        assertThat(policy.permits(request("POST", "/api/workspaces/{workspaceId}/naver/stores/{assetId}/products",
                variables), handler, operate)).isTrue();
        assertThat(policy.permits(request("POST", "/api/workspaces/{workspaceId}/naver/stores/{assetId}/products",
                variables), handler, read)).isFalse();
    }

    @Test void orderBuyerDataSettlementAndClaimActionsAreDeniedInBothSupportModes() {
        var variables = Map.of("workspaceId", "10", "assetId", "30", "productOrderId", "2026092900000001");
        for (var context : new SupportContext[]{read, operate}) {
            for (String suffix : new String[]{"/order-options", "/orders", "/orders/{productOrderId}", "/settlements"}) {
                assertThat(policy.permits(request("GET", "/api/workspaces/{workspaceId}/naver/stores/{assetId}" + suffix,
                        variables), handler, context)).as(context.accessMode() + " " + suffix).isFalse();
            }
            assertThat(policy.permits(request("POST", "/api/workspaces/{workspaceId}/naver/stores/{assetId}/orders/{productOrderId}/actions",
                    variables), handler, context)).isFalse();
        }
    }

    private static SupportContext context(String mode) {
        return new SupportContext(1, 2, 10, 3, 4, mode, LocalDateTime.now().plusMinutes(30));
    }
    private static MockHttpServletRequest request(String method, String pattern, Map<String, String> vars) {
        String uri = pattern;
        for (var entry : vars.entrySet()) uri = uri.replace("{" + entry.getKey() + "}", entry.getValue());
        var request = new MockHttpServletRequest(method, uri);
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, pattern);
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, vars);
        return request;
    }
}
