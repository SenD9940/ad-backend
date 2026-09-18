package com.orinan.api.config.objectmapper;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.DateTimeFeature;

/**
 * Spring Boot 4 / Jackson 3: LocalDateTime이 컨텍스트 타임존으로 밀리지 않도록 고정.
 */
@Configuration
public class JsonMapperConfig {

    @Bean
    JsonMapperBuilderCustomizer localDateTimeWallClockCustomizer() {
        return builder -> builder
                .disable(DateTimeFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
                .disable(DateTimeFeature.WRITE_DATES_WITH_CONTEXT_TIME_ZONE)
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }
}
