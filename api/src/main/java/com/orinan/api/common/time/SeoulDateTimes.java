package com.orinan.api.common.time;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 앱 표시·스케줄 기준 시각은 Asia/Seoul 벽시계로 통일합니다.
 * JVM 기본 타임존(UTC 등)에 의존하지 않습니다.
 */
public final class SeoulDateTimes {

    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private static final DateTimeFormatter WALL_CLOCK =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private SeoulDateTimes() {
    }

    public static LocalDateTime now() {
        return LocalDateTime.now(ZONE);
    }

    public static String formatWallClock(LocalDateTime value) {
        if (value == null) {
            return null;
        }
        return value.format(WALL_CLOCK);
    }
}
