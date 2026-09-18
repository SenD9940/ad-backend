package com.orinan.db.user.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum UserRole {

    ADMIN("어드민"),
    CUSTOMER("고객")

    ;

    private final String description;
}
