package com.orinan.api.domain.platformconnection.controller;

import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.platformconnection.business.PlatformConnectionBusiness;
import com.orinan.api.domain.platformconnection.meta.MetaProperties;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.WebUtils;

import java.net.URI;
import java.time.Duration;

@RestController
@RequiredArgsConstructor
public class MetaOAuthCallbackController {

    public static final String CALLBACK_PATH = "/open-api/platform-connections/meta/callback";
    public static final String STATE_COOKIE_PREFIX = "meta_oauth_state_";
    private final PlatformConnectionBusiness business;
    private final MetaProperties properties;

    @GetMapping(CALLBACK_PATH)
    public ResponseEntity<Void> callback(
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String error,
            HttpServletRequest request
    ) {
        properties.validate();
        boolean validState = isValidState(state);
        var redirect = UriComponentsBuilder.fromUriString(properties.getFrontendRedirectUri())
                .replaceQueryParam("status").replaceQueryParam("workspace_id")
                .replaceQueryParam("connection_id").replaceQueryParam("error_code");
        try {
            if (!validState) {
                throw new ApiException(ApiCode.BAD_REQUEST, "유효하지 않은 Meta 연결 요청입니다. 다시 연결해 주세요.");
            }
            var cookie = WebUtils.getCookie(request, STATE_COOKIE_PREFIX + state);
            String browserState = cookie == null ? null : cookie.getValue();
            var connection = business.completeMetaAuthorization(state, browserState, code, error);
            redirect.replaceQueryParam("status", "success")
                    .replaceQueryParam("workspace_id", connection.workspaceId())
                    .replaceQueryParam("connection_id", connection.id());
        } catch (ApiException exception) {
            // Never forward OAuth codes, tokens or upstream error descriptions to the frontend.
            redirect.replaceQueryParam("status", "error").replaceQueryParam("error_code", exception.getCodeIfs().getCode());
        }
        var response = ResponseEntity.status(HttpStatus.FOUND).cacheControl(CacheControl.noStore())
                .header("Referrer-Policy", "no-referrer");
        if (validState) {
            response.header(HttpHeaders.SET_COOKIE, stateCookie(properties, state, Duration.ZERO).toString());
        }
        return response.location(redirect.build().encode().toUri()).build();
    }

    static ResponseCookie stateCookie(MetaProperties properties, String state, Duration maxAge) {
        if (!isValidState(state)) {
            throw new IllegalArgumentException("Invalid Meta OAuth state");
        }
        return ResponseCookie.from(STATE_COOKIE_PREFIX + state, maxAge.isZero() ? "" : state).httpOnly(true)
                .secure("https".equalsIgnoreCase(URI.create(properties.getRedirectUri()).getScheme()))
                .sameSite("Lax").path(CALLBACK_PATH).maxAge(maxAge).build();
    }

    private static boolean isValidState(String state) {
        return state != null && state.matches("[A-Za-z0-9_-]{43}");
    }
}
