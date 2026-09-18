package com.orinan.api.exceptionhandler;

import com.orinan.api.common.api.Api;
import com.orinan.api.common.code.ApiCode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.util.DisconnectedClientHelper;

@Slf4j
@RestControllerAdvice
@Order(value = Integer.MAX_VALUE) //핸들러 우선 순위 지정 우선순위 가장 낮음 설정
public class GlobalExceptionHandler {

    @ExceptionHandler(value = Exception.class)
    public ResponseEntity<Api<Object>> exception(Exception exception, HttpServletRequest request){
        // 화면 이동·요청 취소 등으로 연결이 닫혔다면 오류 JSON을 다시 쓰지 않습니다.
        // Spring의 판별기는 외부 API·DB 연결 오류를 구분하므로 실제 서버 오류는 아래에서 처리합니다.
        if (DisconnectedClientHelper.isClientDisconnectedException(exception)) {
            // 쿼리 문자열에는 OAuth code 등이 포함될 수 있어 메서드와 경로만 기록합니다.
            log.debug("Client disconnected while responding: {} {}", request.getMethod(), request.getRequestURI());
            return null;
        }
        log.error("Unhandled server exception", exception);

        return ResponseEntity
                .status(500)
                .body(Api.ERROR(ApiCode.SERVER_ERROR, "서버 오류가 발생하였습니다"));
    }
}
