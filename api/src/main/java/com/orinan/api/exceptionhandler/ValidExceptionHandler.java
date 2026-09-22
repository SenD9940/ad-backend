package com.orinan.api.exceptionhandler;


import com.orinan.api.common.api.Api;
import com.orinan.api.common.code.ApiCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.util.List;

@Slf4j
@RestControllerAdvice
@Order(value = Integer.MIN_VALUE)
public class ValidExceptionHandler {

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Api<Object>> uploadTooLarge(MaxUploadSizeExceededException exception) {
        return ResponseEntity.status(413).body(Api.ERROR(ApiCode.BAD_REQUEST, "업로드 가능한 파일 크기를 초과했습니다."));
    }


    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class, MissingServletRequestPartException.class})
    public ResponseEntity<Api<Object>> invalidFormat(Exception exception) {
        return ResponseEntity.badRequest().body(Api.ERROR(ApiCode.BAD_REQUEST, "요청 형식을 확인해 주세요."));
    }

    @ExceptionHandler(value = MethodArgumentNotValidException.class)
    public ResponseEntity<Api<Object>> exception(MethodArgumentNotValidException exception){
        // Binding exceptions include rejected values, which may contain passwords or API secrets.
        log.warn("validation failed fields: {}", exception.getBindingResult().getFieldErrors()
                .stream().map(FieldError::getField).distinct().toList());

        List<String> errorMessages = exception.getBindingResult().getFieldErrors()
                .stream()
                .map(FieldError::getDefaultMessage) // 검증 실패 메시지만 추출
                .toList();

        return ResponseEntity
                .status(400)
                .body(Api.ERROR(ApiCode.BAD_REQUEST, errorMessages.get(0)));
    }
}
