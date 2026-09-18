package com.orinan.db.platformasset.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum PlatformType {

    FACEBOOK("페이스북"),
    INSTAGRAM("인스타"),
    THREADS("쓰레드"),
    GOOGLE_ADS("구글"),
    NAVER_ADS("네이버")
    ;

    private final String description;
}
