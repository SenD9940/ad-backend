package com.orinan.api.config.objectmapper;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.cfg.DateTimeFeature;

/**
 * Spring Boot 4 / Jackson 3: 스네이크 케이스와 LocalDateTime 벽시계 설정.
 */
@Configuration
public class JsonMapperConfig {

    @Bean
    JsonMapperBuilderCustomizer localDateTimeWallClockCustomizer() {
        return builder -> builder
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .disable(DateTimeFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
                .disable(DateTimeFeature.WRITE_DATES_WITH_CONTEXT_TIME_ZONE)
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }
}
