package com.orinan.adminapi.domain.auth.controller.model;

import java.time.Instant;

public record AdminLoginResponse(String accessToken, String tokenType, Instant expiresAt,
                                 long expiresIn, AdminMeResponse user) {
    @Override public String toString() { return "AdminLoginResponse[redacted]"; }
}
