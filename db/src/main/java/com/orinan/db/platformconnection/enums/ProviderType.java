package com.orinan.db.platformconnection.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ProviderType {

    META("메타"),
    THREADS("쓰레드"),
    GOOGLE("구글"),
    NAVER("네이버")

    ;

    private final String description;
}
