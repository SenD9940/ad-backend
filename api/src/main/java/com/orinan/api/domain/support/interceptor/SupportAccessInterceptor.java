package com.orinan.api.domain.support.interceptor;

import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.support.exception.SupportErrorCode;
import com.orinan.api.domain.support.model.SupportContext;
import com.orinan.api.domain.support.service.SupportActionService;
import com.orinan.api.domain.support.service.SupportRoutePolicy;
import com.orinan.api.domain.support.service.SupportSessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Collections;

/** Runs even for public handlers. A support header can never fall back to another identity. */
@Component
@RequiredArgsConstructor
@Slf4j
public class SupportAccessInterceptor implements HandlerInterceptor {
    public static final String HEADER = "X-Support-Token";
    private static final String ACTION_ATTRIBUTE = SupportAccessInterceptor.class.getName() + ".actionId";
    private final SupportSessionService sessions;
    private final SupportRoutePolicy policy;
    private final SupportActionService actions;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        var headers = Collections.list(request.getHeaders(HEADER));
        if (headers.isEmpty()) return true;
        response.setHeader("Cache-Control", "no-store");
        if (headers.size() != 1 || !headers.get(0).matches("[A-Za-z0-9_-]{43}")) {
            throw new ApiException(SupportErrorCode.INVALID_SESSION);
        }
        SupportContext context = sessions.authenticate(headers.get(0));
        boolean permitted = policy.permits(request, handler, context);
        long actionId;
        try {
            actionId = actions.begin(context, request.getMethod(), policy.auditRoute(request, permitted));
        } catch (RuntimeException exception) {
            throw new ApiException(SupportErrorCode.AUDIT_UNAVAILABLE);
        }
        if (!permitted) {
            actions.complete(actionId, 403);
            throw new ApiException(SupportErrorCode.ACCESS_DENIED);
        }
        request.setAttribute(ACTION_ATTRIBUTE, actionId);
        request.setAttribute(SupportContext.REQUEST_ATTRIBUTE, context);
        request.setAttribute("userId", context.customerUserId());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception exception) {
        if (!(request.getAttribute(ACTION_ATTRIBUTE) instanceof Long actionId)) return;
        int status = exception != null && response.getStatus() < 400 ? 500 : response.getStatus();
        try {
            actions.complete(actionId, status);
        } catch (RuntimeException ignored) {
            // An admitted remote write might have succeeded. Preserve its pending audit row and response; never retry it.
            log.warn("Support action completion remains pending: actionId={}", actionId);
        }
    }
}
