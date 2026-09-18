package com.orinan.api.common.code;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@AllArgsConstructor
@Getter
public enum DatabaseErrorCode implements CodeIfs{

    DATA_INTEGRITY_VIOLATION(HttpStatus.INTERNAL_SERVER_ERROR.value(), 1205, "저장 오류가 발생 하였습니다, 필수 값이 없음"),

    DUPLICATE_KEY(HttpStatus.CONFLICT.value(), 1201, "중복된키 입니다 처음부터 다시 시도해 주세요"),

    DELETE_ERROR(HttpStatus.INTERNAL_SERVER_ERROR.value(), 1205, "토큰 삭제에 실패 하였습니다")

    ;

    private final Integer httpStatusCode;

    private final Integer code;

    private final String description;
}
