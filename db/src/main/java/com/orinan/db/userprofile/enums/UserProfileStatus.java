package com.orinan.db.userprofile.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum UserProfileStatus {

    REGISTERED("등록됨"),
    UNREGISTERED("등록 해제 됨")

    ;

    private final String description;
}
