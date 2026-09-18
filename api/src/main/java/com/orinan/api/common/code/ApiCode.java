package com.orinan.api.common.code;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@AllArgsConstructor
@Getter
public enum ApiCode implements CodeIfs{

    OK(200, 200, "성공"),

    BAD_REQUEST(HttpStatus.BAD_REQUEST.value(), 400, "잘못된 요청입니다"),

    SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR.value(), 500, "서버 에러가 발생하였습니다"),

    NULL_POINT(HttpStatus.INTERNAL_SERVER_ERROR.value(), 512, "데이터가 존재하지 않습니다")

    ;

    private final Integer httpStatusCode;

    private final Integer code;

    private final String description;
}
