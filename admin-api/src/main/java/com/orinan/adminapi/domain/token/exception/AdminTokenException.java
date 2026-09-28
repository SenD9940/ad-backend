package com.orinan.adminapi.domain.token.exception;

import com.orinan.adminapi.common.exception.AdminException;

public class AdminTokenException extends AdminException {
    private final AdminTokenErrorCode errorCode;

    public AdminTokenException(AdminTokenErrorCode errorCode) {
        super(errorCode.status(), errorCode.message());
        this.errorCode = errorCode;
    }

    public AdminTokenErrorCode errorCode() { return errorCode; }

    public AdminTokenErrorCode getErrorCode() { return errorCode; }
}
