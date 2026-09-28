package com.orinan.adminapi.config.security;

import com.orinan.adminapi.domain.auth.business.AdminAuthBusiness;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
public class AdminSecurityConfig {
    @Bean
    public PasswordEncoder adminPasswordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean
    public AdminAuthenticationFilter adminAuthenticationFilter(AdminAuthBusiness auth, AdminSecurityResponses responses) {
        return new AdminAuthenticationFilter(auth, responses);
    }

    @Bean
    public FilterRegistrationBean<AdminAuthenticationFilter> adminFilterRegistration(AdminAuthenticationFilter filter) {
        var registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public SecurityFilterChain adminSecurityFilterChain(HttpSecurity http, AdminAuthenticationFilter filter,
                                                        AdminSecurityResponses responses,
                                                        CorsConfigurationSource adminCorsConfigurationSource) throws Exception {
        return http
                .cors(cors -> cors.configurationSource(adminCorsConfigurationSource))
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) ->
                                responses.error(response, HttpStatus.UNAUTHORIZED, "관리자 로그인이 필요합니다."))
                        .accessDeniedHandler((request, response, exception) ->
                                responses.error(response, HttpStatus.FORBIDDEN, "관리자 권한이 필요합니다.")))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(HttpMethod.POST, "/admin-api/auth/login").permitAll()
                        .requestMatchers("/admin-api/**", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").hasRole("ADMIN")
                        .anyRequest().denyAll())
                .addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    public CorsConfigurationSource adminCorsConfigurationSource(@Value("${app.admin.allowed-origins:}") String origins) {
        var configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(Arrays.stream(origins.split(",")).map(String::strip)
                .filter(value -> !value.isBlank()).filter(value -> !"*".equals(value)).toList());
        configuration.setAllowedMethods(List.of("GET", "POST", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(600L);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
