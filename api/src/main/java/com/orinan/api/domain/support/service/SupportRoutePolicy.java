package com.orinan.api.domain.support.service;

import com.orinan.api.domain.support.model.SupportContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;
import java.util.Set;

/** Explicit routes only: new service endpoints never become support permissions by default. */
@Component
public class SupportRoutePolicy {
    private static final String WORKSPACE = "/api/workspaces/{workspaceId}";
    private static final String META = WORKSPACE + "/meta";
    private static final String ACCOUNT = META + "/ad-accounts/{assetId}";
    private static final String CONNECTION = WORKSPACE + "/connections";
    private static final String STORE = WORKSPACE + "/naver/stores/{assetId}";
    private static final Set<String> READS = Set.of(
            WORKSPACE, CONNECTION,
            CONNECTION + "/{connectionId}/meta/assets",
            CONNECTION + "/{connectionId}/naver/channels",
            META + "/insights", ACCOUNT + "/campaigns", ACCOUNT + "/insights", ACCOUNT + "/pages",
            WORKSPACE + "/naver/stores", STORE + "/products", STORE + "/sales",
            STORE + "/product-creation/options", STORE + "/product-creation/notices");
    private static final Set<String> CREATES = Set.of(
            ACCOUNT + "/ads", ACCOUNT + "/campaigns", ACCOUNT + "/images", ACCOUNT + "/pages",
            CONNECTION + "/{connectionId}/meta/assets", CONNECTION + "/{connectionId}/naver/channels",
            STORE + "/products");
    private static final Set<String> UPDATES = Set.of(
            ACCOUNT + "/ads/{adId}", ACCOUNT + "/ad-sets/{adSetId}", ACCOUNT + "/campaigns/{campaignId}");

    public boolean permits(HttpServletRequest request, Object handler, SupportContext context) {
        if (!(handler instanceof HandlerMethod)) return false;
        var attribute = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (!(attribute instanceof String pattern)) return false;
        String method = request.getMethod();
        if (pattern.equals("/api/support/session") && method.equals("GET")) return canonical(request, pattern, Map.of());
        if (pattern.equals("/api/support/session/end") && method.equals("POST")) return canonical(request, pattern, Map.of());
        var variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (!(variables instanceof Map<?, ?> values)) return false;
        var workspaceId = values.get("workspaceId");
        if (!(workspaceId instanceof String id) || !id.equals(Long.toString(context.workspaceId()))) return false;
        if (!canonical(request, pattern, values)) return false;
        if (method.equals("GET")) return READS.contains(pattern);
        if (!context.accessMode().equals("OPERATE")) return false;
        return method.equals("POST") && CREATES.contains(pattern) || method.equals("PATCH") && UPDATES.contains(pattern);
    }

    public String auditRoute(HttpServletRequest request, boolean permitted) {
        // Canonical admitted routes contain only the fixed route plus numeric IDs, never a query or credential.
        if (permitted && request.getRequestURI().length() <= 500) return request.getRequestURI();
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return pattern instanceof String value && value.length() <= 255 ? value : "UNSUPPORTED_ROUTE";
    }

    private boolean canonical(HttpServletRequest request, String pattern, Map<?, ?> values) {
        String path = pattern;
        for (var entry : values.entrySet()) {
            if (!(entry.getKey() instanceof String key) || !(entry.getValue() instanceof String value)
                    || !value.matches("[1-9][0-9]*")) return false;
            path = path.replace("{" + key + "}", value);
        }
        return !path.contains("{") && request.getRequestURI().equals(request.getContextPath() + path);
    }
}
