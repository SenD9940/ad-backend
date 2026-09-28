package com.orinan.api.domain.aistudio.controller;

import com.orinan.api.common.api.Api;
import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.aistudio.exception.AiStudioErrorCode;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

@RestControllerAdvice(assignableTypes = AiStudioController.class)
@Order(0)
public class AiStudioExceptionHandler {
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Api<Object>> api(ApiException error) {
        return ResponseEntity.status(error.getCodeIfs().getHttpStatusCode()).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(Api.ERROR(error.getCodeIfs(), error.getDescription()));
    }
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentNotValidException.class,
            MethodArgumentTypeMismatchException.class, MissingServletRequestPartException.class})
    public ResponseEntity<Api<Object>> invalid(Exception ignored) { return api(new ApiException(AiStudioErrorCode.INVALID_REQUEST)); }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Api<Object>> unknown(Exception ignored) { return api(new ApiException(AiStudioErrorCode.PROVIDER_FAILURE)); }
}
