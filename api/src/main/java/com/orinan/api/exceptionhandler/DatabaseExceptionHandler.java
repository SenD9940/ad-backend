package com.orinan.api.exceptionhandler;

import com.orinan.api.common.api.Api;
import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.code.DatabaseErrorCode;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PessimisticLockException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.sql.SQLException;

@Slf4j
@RestControllerAdvice
@Order(2)
public class DatabaseExceptionHandler {

    private static final int MYSQL_DUPLICATE_ENTRY_ERROR_CODE = 1062;

    /**
     * 비관적 락 획득 실패 또는 락 대기시간 초과
     */
    @ExceptionHandler({
            PessimisticLockException.class,
            LockTimeoutException.class
    })
    public ResponseEntity<Api<Object>> handleLockException(
            RuntimeException exception
    ) {
        log.error("database lock timeout occurred", exception);

        return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(
                        Api.ERROR(
                                ApiCode.SERVER_ERROR,
                                "요청 시간이 초과되었습니다.\n잠시 후 다시 시도해 주세요."
                        )
                );
    }

    /**
     * 데이터 무결성 제약조건 위반
     *
     * 중복 키 역시 JPA에서는 일반적으로
     * DataIntegrityViolationException으로 감싸져 전달된다.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Api<Object>> handleDataIntegrityViolation(
            DataIntegrityViolationException exception
    ) {
        SQLException sqlException = findSQLException(exception);

        if (isDuplicateEntry(sqlException)) {
            log.warn(
                    "duplicate key violation: sqlState={}, errorCode={}, message={}",
                    sqlException.getSQLState(),
                    sqlException.getErrorCode(),
                    sqlException.getMessage()
            );

            return ResponseEntity
                    .status(HttpStatus.CONFLICT)
                    .body(Api.ERROR(DatabaseErrorCode.DUPLICATE_KEY));
        }

        log.error(
                "data integrity violation: rootCause={}",
                getRootCauseMessage(exception),
                exception
        );

        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(
                        Api.ERROR(
                                DatabaseErrorCode.DATA_INTEGRITY_VIOLATION
                        )
                );
    }

    /**
     * MySQL Duplicate Entry 오류인지 확인
     */
    private boolean isDuplicateEntry(SQLException sqlException) {
        return sqlException != null
                && sqlException.getErrorCode()
                == MYSQL_DUPLICATE_ENTRY_ERROR_CODE;
    }

    /**
     * 예외 체인에서 SQLException 탐색
     */
    private SQLException findSQLException(Throwable throwable) {
        Throwable cause = throwable;

        while (cause != null) {
            if (cause instanceof SQLException sqlException) {
                return sqlException;
            }

            cause = cause.getCause();
        }

        return null;
    }

    /**
     * 가장 마지막 원인 예외 메시지 반환
     */
    private String getRootCauseMessage(Throwable throwable) {
        Throwable rootCause = throwable;

        while (rootCause.getCause() != null) {
            rootCause = rootCause.getCause();
        }

        return rootCause.getMessage();
    }
}