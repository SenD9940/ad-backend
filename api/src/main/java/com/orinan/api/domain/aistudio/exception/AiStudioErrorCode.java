package com.orinan.api.domain.aistudio.exception;

import com.orinan.api.common.code.CodeIfs;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum AiStudioErrorCode implements CodeIfs {
    INVALID_REQUEST(400, 8200, "AI 스튜디오 입력 값을 확인해 주세요."),
    ACCESS_DENIED(403, 8201, "이 워크스페이스의 AI 스튜디오를 사용할 수 없습니다."),
    NOT_FOUND(404, 8202, "AI 스튜디오 항목을 찾을 수 없습니다."),
    UNAVAILABLE(503, 8203, "AI 생성 기능을 준비 중입니다. 잠시 후 다시 확인해 주세요."),
    GENERATION_IN_PROGRESS(409, 8204, "생성 요청을 처리 중입니다. 같은 요청으로 결과를 다시 확인해 주세요."),
    GENERATION_FAILED(409, 8205, "이 생성 요청은 완료되지 않았습니다. 자동으로 다시 생성하지 않습니다. 새로 생성하려면 새 요청을 시작해 주세요."),
    IDEMPOTENCY_CONFLICT(409, 8206, "같은 요청 번호의 입력 내용이 다릅니다. 기존 요청의 결과를 확인해 주세요."),
    PROVIDER_FAILURE(502, 8207, "AI 생성 결과를 확인하지 못했습니다. 요청이 자동으로 재실행되지는 않습니다."),
    STORAGE_FAILURE(503, 8208, "AI 이미지 저장소에 접근할 수 없습니다. 잠시 후 다시 확인해 주세요.");
    private final Integer httpStatusCode;
    private final Integer code;
    private final String description;
}
