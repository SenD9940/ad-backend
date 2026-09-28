package com.orinan.adminapi.domain.token.model;

import java.time.Instant;

public record AdminTokenDto(String token, Instant expiredAt, long expiresIn) {
    @Override
    public String toString() {
        return "AdminTokenDto[redacted]";
    }
}
