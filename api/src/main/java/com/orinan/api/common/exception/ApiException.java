package com.orinan.api.common.exception;

import com.orinan.api.common.code.CodeIfs;
import lombok.Getter;

@Getter
public class ApiException extends RuntimeException implements ApiExceptionIfs{

    private final CodeIfs codeIfs;
    private final String description;

    public ApiException(CodeIfs codeIfs) {
        super(codeIfs.getDescription());
        this.codeIfs = codeIfs;
        this.description = codeIfs.getDescription();
    }

    public ApiException(CodeIfs codeIfs, String description) {
        super(description);
        this.codeIfs = codeIfs;
        this.description = description;
    }

    public ApiException(CodeIfs errorCodeIfs, Throwable tx){
        super(tx);
        this.codeIfs = errorCodeIfs;
        this.description = errorCodeIfs.getDescription();
    }


    public ApiException(CodeIfs codeIfs, String description, Throwable tx) {
        super(tx);
        this.codeIfs = codeIfs;
        this.description = description;
    }

}
