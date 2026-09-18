package com.orinan.api.common.api;

import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.code.CodeIfs;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@NoArgsConstructor
@AllArgsConstructor
@Builder
@Getter
public class Result {

    private Integer resultCode;
    private String resultMessage;
    private String resultDescription;

    public static Result OK(){
        return Result.builder()
                .resultCode(ApiCode.OK.getHttpStatusCode())
                .resultMessage(ApiCode.OK.getDescription())
                .resultDescription("성공")
                .build();
    }

    public static Result ERROR(CodeIfs codeIfs){
        return Result.builder()
                .resultCode(codeIfs.getCode())
                .resultMessage(codeIfs.getDescription())
                .resultDescription("에러")
                .build();
    }

    public static Result ERROR(CodeIfs codeIfs, Throwable tx){
        return Result.builder()
                .resultCode(codeIfs.getCode())
                .resultMessage(codeIfs.getDescription())
                .resultDescription(tx.getLocalizedMessage())
                .build();
    }

    public static Result ERROR(CodeIfs codeIfs, String description){
        return Result.builder()
                .resultCode(codeIfs.getCode())
                .resultMessage(codeIfs.getDescription())
                .resultMessage(description)
                .build();
    }
}
