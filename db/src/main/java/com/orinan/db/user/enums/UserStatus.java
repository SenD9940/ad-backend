package com.orinan.db.user.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum UserStatus {

    REGISTERED("등록됨"),
    SUSPENDED("이용 정지"),
    UNREGISTERED("등록 해제 됨")

    ;

    private final String description;
}
