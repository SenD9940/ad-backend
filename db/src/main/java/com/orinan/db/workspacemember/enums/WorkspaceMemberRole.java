package com.orinan.db.workspacemember.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum WorkspaceMemberRole {

    MEMBER("등록됨"),

    ;

    private final String description;
}
