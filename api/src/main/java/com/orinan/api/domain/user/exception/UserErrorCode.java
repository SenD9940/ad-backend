package com.orinan.api.domain.user.exception;

import com.orinan.api.common.code.CodeIfs;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@AllArgsConstructor
@Getter
public enum UserErrorCode implements CodeIfs {


    USER_NOT_FOUND(HttpStatus.CONFLICT.value(), 3404, "존재하지 않는 유저 입니다"),

    USER_PERMISSION_DENY(HttpStatus.FORBIDDEN.value(), 3403, "권한이 없습니다"),

    USER_PASSWORD_MISMATCH(HttpStatus.UNAUTHORIZED.value(), 3401, "비밀번호가 일치하지 않습니다"),
    INVALID_CREDENTIALS(401, 3410, "이메일 또는 비밀번호가 올바르지 않습니다."),
    EMAIL_ALREADY_EXISTS(409, 3409, "이미 사용 중인 이메일입니다."),
    PASSWORD_TOO_LONG(400, 3411, "비밀번호는 UTF-8 기준 72바이트 이하여야 합니다.")

    ;

    private final Integer httpStatusCode;

    private final Integer code;

    private final String description;
}
