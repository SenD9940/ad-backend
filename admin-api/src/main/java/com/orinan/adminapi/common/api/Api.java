package com.orinan.adminapi.common.api;

import jakarta.validation.Valid;

/** Uses the service API's result/body envelope while retaining the administrator API contract. */
public record Api<T>(Result result, @Valid T body) {
    public static <T> Api<T> OK(T body) {
        return new Api<>(Result.OK(), body);
    }

    public static Api<Void> ERROR(int status, String message) {
        return new Api<>(Result.ERROR(status, message), null);
    }
}
