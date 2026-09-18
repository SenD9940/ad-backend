package com.orinan.db.token.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum TokenStatus {

    ACTIVE("활성화 됨"),
    EXPIRED("만료됨")

    ;

    private final String description;
}
