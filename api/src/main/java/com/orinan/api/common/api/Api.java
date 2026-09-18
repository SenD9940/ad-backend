package com.orinan.api.common.api;

import com.orinan.api.common.code.CodeIfs;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Api<T>{

    private Result result;

    private @Valid T body;

    public static <T> Api<T> OK(T body) {
        var api = new Api<T>();
        api.result = Result.OK();
        api.body = body;
        return api;
    }

    public static Api<Object> ERROR(Result result){
        var api = new Api<Object>();
        api.result = result;
        return api;
    }

    public static Api<Object> ERROR(CodeIfs codeIfs){
        var api = new Api<Object>();
        api.result = Result.ERROR(codeIfs);
        return api;
    }

    public static Api<Object> ERROR(CodeIfs codeIfs, Throwable tx){
        var api = new Api<Object>();
        api.result = Result.ERROR(codeIfs, tx);
        return api;
    }

    public static Api<Object> ERROR(CodeIfs errorCodeIfs, String description){
        var api = new Api<Object>();
        api.result = Result.ERROR(errorCodeIfs, description);
        return api;
    }
}
