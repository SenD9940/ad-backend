package com.orinan.adminapi.domain.token.controller.model;

import java.time.Instant;

public record AdminTokenResponse(String accessToken, String tokenType, Instant expiresAt, long expiresIn) {
    @Override
    public String toString() {
        return "AdminTokenResponse[redacted]";
    }
}
