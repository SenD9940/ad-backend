package com.orinan.api.domain.support.exception;

import com.orinan.api.common.code.CodeIfs;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum SupportErrorCode implements CodeIfs {
    INVALID_SESSION(401, 8100, "기술 지원 세션이 만료되었거나 사용할 수 없습니다."),
    ACCESS_DENIED(403, 8101, "승인된 기술 지원 범위에서 사용할 수 없는 기능입니다."),
    INVALID_REQUEST(400, 8102, "기술 지원 요청 값을 확인해 주세요."),
    NOT_FOUND(404, 8103, "기술 지원 요청을 찾을 수 없습니다."),
    CONFLICT(409, 8104, "기술 지원 요청 상태가 변경되었습니다. 새로고침 후 확인해 주세요."),
    AUDIT_UNAVAILABLE(503, 8105, "지원 작업 이력을 저장할 수 없습니다. 잠시 후 다시 시도해 주세요."),
    PAYMENT_UNAVAILABLE(503, 8106, "기술 지원 결제 준비 중입니다. 잠시 후 다시 확인해 주세요."),
    TERMS_CHANGED(409, 8107, "기술 지원 안내가 변경되었습니다. 최신 약관을 확인하고 다시 신청해 주세요.");

    private final Integer httpStatusCode;
    private final Integer code;
    private final String description;
}
