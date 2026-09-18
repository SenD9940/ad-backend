package com.orinan.api.domain.token.exception;

import com.orinan.api.common.code.CodeIfs;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum TokenErrorCode implements CodeIfs {

    INVALID_TOKEN(401, 2000, "유효하지 않은 토큰"),

    EXPIRED_TOKEN(401, 2001, "만료된 토큰"),

    TOKEN_EXCEPTION(401, 2505, "알 수 없는 토큰 에러"),

    AUTHORIZATION_TOKEN_NOT_FOUND(401, 2404, "인증 헤더에 토큰이 없음")

    ;

    private final Integer httpStatusCode;

    private final Integer code;

    private final String description;
}
