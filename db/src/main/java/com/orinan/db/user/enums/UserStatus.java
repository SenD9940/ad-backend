package com.orinan.db.user.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum UserStatus {

    REGISTERED("등록됨"),
    UNREGISTERED("등록 해제 됨")

    ;

    private final String description;
}
