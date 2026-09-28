package com.orinan.adminapi.domain.token.exception;

import org.springframework.http.HttpStatus;

public enum AdminTokenErrorCode {
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "관리자 로그인이 필요합니다."),
    EXPIRED_TOKEN(HttpStatus.UNAUTHORIZED, "관리자 로그인이 필요합니다."),
    TOKEN_EXCEPTION(HttpStatus.UNAUTHORIZED, "관리자 로그인이 필요합니다."),
    ADMIN_PERMISSION_DENIED(HttpStatus.FORBIDDEN, "관리자 권한이 필요합니다."),
    SESSION_VERSION_EXHAUSTED(HttpStatus.CONFLICT, "세션 버전이 허용 범위를 초과했습니다.");

    private final HttpStatus status;
    private final String message;

    AdminTokenErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus status() { return status; }

    public String message() { return message; }
}
