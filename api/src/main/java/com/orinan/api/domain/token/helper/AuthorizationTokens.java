package com.orinan.api.domain.token.helper;

import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.token.exception.TokenErrorCode;

public final class AuthorizationTokens {
    private AuthorizationTokens() {}

    public static String extract(String header) {
        if (header == null || header.isBlank()) {
            throw new ApiException(TokenErrorCode.AUTHORIZATION_TOKEN_NOT_FOUND);
        }
        String token = header.strip();
        if (token.regionMatches(true, 0, "Bearer ", 0, 7)) {
            token = token.substring(7).strip();
        }
        // 기존 클라이언트의 raw JWT 헤더도 허용합니다.
        if (token.isBlank() || token.chars().anyMatch(Character::isWhitespace)
                || token.equalsIgnoreCase("Bearer")) {
            throw new ApiException(TokenErrorCode.INVALID_TOKEN);
        }
        return token;
    }
}
