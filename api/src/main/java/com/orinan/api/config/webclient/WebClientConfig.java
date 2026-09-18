package com.orinan.api.config.webclient;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.codec.json.JacksonJsonDecoder;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class WebClientConfig {

    /**
     * Spring Boot 4 / Jackson 3 기반 WebClient JSON Codec 설정
     */
    @Bean
    public ExchangeStrategies exchangeStrategies(JsonMapper jsonMapper) {
        return ExchangeStrategies.builder()
                .codecs(configurer -> {
                    configurer.defaultCodecs()
                            .jacksonJsonDecoder(
                                    new JacksonJsonDecoder(jsonMapper)
                            );

                    configurer.defaultCodecs()
                            .jacksonJsonEncoder(
                                    new JacksonJsonEncoder(jsonMapper)
                            );
                })
                .build();
    }

    @Bean
    public WebClient.Builder webClientBuilder(
            ExchangeStrategies exchangeStrategies
    ) {
        return WebClient.builder()
                .exchangeStrategies(exchangeStrategies);
    }
}