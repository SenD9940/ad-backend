package com.orinan.adminapi.common.exception;

import org.springframework.http.HttpStatus;

public class AdminException extends RuntimeException {
    private final HttpStatus status;
    public AdminException(HttpStatus status, String message) { super(message); this.status = status; }
    public HttpStatus status() { return status; }
    public HttpStatus getStatus() { return status; }
}
