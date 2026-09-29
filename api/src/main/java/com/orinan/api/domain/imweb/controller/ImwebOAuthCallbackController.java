package com.orinan.api.domain.imweb.controller;

import com.orinan.api.domain.imweb.business.ImwebConnectionBusiness;
import com.orinan.api.domain.imweb.client.ImwebProperties;
import com.orinan.api.domain.imweb.service.ImwebOAuthStateService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.WebUtils;
import java.net.URI;
import java.time.Duration;

@RestController @RequiredArgsConstructor
public class ImwebOAuthCallbackController {
    public static final String CALLBACK_PATH = "/open-api/platform-connections/imweb/callback";
    private static final String COOKIE_PREFIX = "imweb_oauth_state_";
    private final ImwebConnectionBusiness business;
    private final ImwebProperties properties;
    @GetMapping(CALLBACK_PATH)
    public ResponseEntity<Void> callback(@RequestParam(required = false) String state,
                                         @RequestParam(required = false) String code,
                                         @RequestParam(required = false) String error, HttpServletRequest request) {
        properties.validate();
        boolean valid = ImwebOAuthStateService.validState(state);
        var redirect = UriComponentsBuilder.fromUriString(properties.getFrontendRedirectUri());
        try {
            var browser = valid ? WebUtils.getCookie(request, COOKIE_PREFIX + state) : null;
            var result = business.complete(state, browser == null ? null : browser.getValue(), code, error);
            redirect.queryParam("status", "success").queryParam("workspace_id", result.workspaceId()).queryParam("connection_id", result.id());
        } catch (RuntimeException failure) {
            // Provider payloads, code, state and credentials are never redirected or logged.
            redirect.queryParam("status", "error").queryParam("error_code", "IMWEB_CONNECTION_FAILED");
        }
        var response = ResponseEntity.status(HttpStatus.FOUND).cacheControl(CacheControl.noStore()).header("Referrer-Policy", "no-referrer");
        if (valid) response.header(HttpHeaders.SET_COOKIE, cookie(properties, state, Duration.ZERO).toString());
        return response.location(redirect.build().encode().toUri()).build();
    }
    static ResponseCookie cookie(ImwebProperties properties, String state, Duration age) {
        if (!ImwebOAuthStateService.validState(state)) throw new IllegalArgumentException("Invalid Imweb state");
        return ResponseCookie.from(COOKIE_PREFIX + state, age.isZero() ? "" : state).httpOnly(true)
                .secure("https".equalsIgnoreCase(URI.create(properties.getRedirectUri()).getScheme()))
                .sameSite("Lax").path(CALLBACK_PATH).maxAge(age).build();
    }
}
