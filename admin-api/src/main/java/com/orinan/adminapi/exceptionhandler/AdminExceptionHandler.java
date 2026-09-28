package com.orinan.adminapi.exceptionhandler;

import com.orinan.adminapi.common.api.Api;
import com.orinan.adminapi.common.exception.AdminException;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class AdminExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(AdminExceptionHandler.class);
    @ExceptionHandler(AdminException.class)
    ResponseEntity<?> admin(AdminException error) { return response(error.status().value(), error.getMessage()); }
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class, ConstraintViolationException.class, HandlerMethodValidationException.class,
            MissingServletRequestParameterException.class})
    ResponseEntity<?> invalid(Exception ignored) { return response(400, "요청 형식과 필수 입력값을 확인해 주세요."); }
    @ExceptionHandler({DataIntegrityViolationException.class, PessimisticLockingFailureException.class})
    ResponseEntity<?> conflict(Exception ignored) { return response(409, "다른 요청과 충돌했습니다. 최신 정보를 조회한 뒤 다시 시도해 주세요."); }
    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<?> missing(Exception ignored) { return response(404, "존재하지 않는 관리 API입니다."); }
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<?> method(Exception ignored) { return response(405, "지원하지 않는 요청 메서드입니다."); }
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<?> mediaType(Exception ignored) { return response(415, "Content-Type을 application/json으로 지정해 주세요."); }
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unexpected(Exception exception) {
        LOG.error("Admin operation failed: {}", exception.getClass().getSimpleName());
        return response(500, "관리 요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.");
    }
    private ResponseEntity<?> response(int code, String message) {
        return ResponseEntity.status(code).header("Cache-Control", "no-store").body(Api.ERROR(code, message));
    }
}
