package com.orinan.api.domain.platformconnection.naver.authorization;
import com.orinan.api.common.code.CodeIfs;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
@Getter @RequiredArgsConstructor
public enum NaverAuthorizationCode implements CodeIfs {
    NOT_FOUND(404,404,"연결 시도를 찾을 수 없습니다."),
    FORBIDDEN(403,403,"연결을 시작한 브라우저와 워크스페이스 소유자를 확인해 주세요."),
    CONFLICT(409,409,"연결 상태가 변경되었습니다. 현재 상태를 다시 확인해 주세요."),
    UNAVAILABLE(503,503,"네이버 인증 연결이 아직 준비되지 않았습니다.");
    private final Integer httpStatusCode; private final Integer code; private final String description;
}
