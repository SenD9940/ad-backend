package com.orinan.adminapi.config.security;

import com.orinan.adminapi.common.api.Api;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
public class AdminSecurityResponses {
    private final JsonMapper mapper;
    public AdminSecurityResponses(JsonMapper mapper) { this.mapper = mapper; }

    public void error(HttpServletResponse response, HttpStatus status, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-store");
        if (status == HttpStatus.UNAUTHORIZED) response.setHeader("WWW-Authenticate", "Bearer");
        response.getWriter().write(mapper.writeValueAsString(Api.ERROR(status.value(), message)));
    }
}
