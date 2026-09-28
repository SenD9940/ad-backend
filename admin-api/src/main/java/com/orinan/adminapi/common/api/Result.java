package com.orinan.adminapi.common.api;

public record Result(int resultCode, String resultMessage, String resultDescription) {
    public static Result OK() {
        return new Result(200, "성공", "성공");
    }

    public static Result ERROR(int status, String message) {
        return new Result(status, message, message);
    }
}
