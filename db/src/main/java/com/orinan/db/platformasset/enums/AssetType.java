package com.orinan.db.platformasset.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum AssetType {

    AD_ACCOUNT("광고 계정"),
    PAGE("광고 페이지"),
    PROFILE("프로필")

    ;

    private final String description;
}
