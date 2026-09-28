package com.orinan.api.domain.support.payment;

import com.orinan.api.common.api.Api;
import com.orinan.api.common.exception.ApiException;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Payment keys can occur in binding/transport errors: never log or return the original exception. */
@RestControllerAdvice(assignableTypes = {SupportPaymentController.class, TossPaymentWebhookController.class})
@Order(0)
public class SupportPaymentExceptionHandler {
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Api<Object>> api(ApiException error) {
        return ResponseEntity.status(error.getCodeIfs().getHttpStatusCode()).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(Api.ERROR(error.getCodeIfs(), error.getDescription()));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentNotValidException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Api<Object>> invalid(Exception ignored) {
        return api(new ApiException(SupportPaymentErrorCode.INVALID_REQUEST));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Api<Object>> unexpected(Exception ignored) {
        return api(new ApiException(SupportPaymentErrorCode.RECONCILE_REQUIRED));
    }
}
