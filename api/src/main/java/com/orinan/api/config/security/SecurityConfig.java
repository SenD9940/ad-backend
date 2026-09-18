package com.orinan.api.config.security;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.security.autoconfigure.web.servlet.PathRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    @Value("${app.cors.allowed-origins:http://localhost:3100}")
    private String allowedOrigins;

    private static final String[] SWAGGER = {
            "/swagger-ui.html",
            "/swagger-ui/**",
            "/v3/api-docs/**"
    };

    private static final String[] PUBLIC_API = {
            "/open-api/**"
    };

    @Bean
    public SecurityFilterChain filterChain(
            HttpSecurity http
    ) throws Exception {

        http
                .cors(cors -> cors.configurationSource(
                        corsConfigurationSource()
                ))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(
                                SessionCreationPolicy.STATELESS
                        )
                )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                PathRequest.toStaticResources()
                                        .atCommonLocations()
                        ).permitAll()
                        .requestMatchers(SWAGGER).permitAll()
                        .requestMatchers(PUBLIC_API).permitAll()
                        /*
                         * API 인증은 AuthorizationInterceptor가 담당합니다.
                         * Spring Security에서 authenticated()를 쓰면
                         * SecurityContext에 인증 정보가 없어 인터셉터 전에 401이 납니다.
                         */
                        .anyRequest().permitAll()
                );

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration =
                new CorsConfiguration();

        configuration.setAllowedOrigins(parseOrigins(allowedOrigins));

        configuration.setAllowedMethods(List.of(
                "GET",
                "POST",
                "PUT",
                "PATCH",
                "DELETE",
                "OPTIONS"
        ));

        configuration.setAllowedHeaders(List.of("*"));

        configuration.setExposedHeaders(List.of(
                "Authorization",
                "Content-Type"
        ));

        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source =
                new UrlBasedCorsConfigurationSource();

        source.registerCorsConfiguration(
                "/**",
                configuration
        );

        return source;
    }

    private static List<String> parseOrigins(String raw) {
        Set<String> origins = new LinkedHashSet<>();
        // 프로필별 YAML에 명시한 출처만 허용합니다.

        if (raw != null && !raw.isBlank()) {
            Arrays.stream(raw.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(SecurityConfig::normalizeOrigin)
                    .forEach(origins::add);
        }

        return origins.stream().collect(Collectors.toList());
    }

    /** scheme 없는 호스트만 오면 https:// 를 붙인다. */
    private static String normalizeOrigin(String value) {
        if (value.startsWith("http://") || value.startsWith("https://")) {
            return value.replaceAll("/+$", "");
        }
        return "https://" + value.replaceAll("/+$", "");
    }
}
