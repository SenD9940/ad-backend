package com.orinan.adminapi.config.security;

import com.orinan.adminapi.domain.auth.business.AdminAuthBusiness;
import com.orinan.adminapi.common.exception.AdminException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

public class AdminAuthenticationFilter extends OncePerRequestFilter {
    private final AdminAuthBusiness auth;
    private final AdminSecurityResponses responses;
    public AdminAuthenticationFilter(AdminAuthBusiness auth, AdminSecurityResponses responses) {
        this.auth = auth;
        this.responses = responses;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        if ("POST".equals(request.getMethod()) && "/admin-api/auth/login".equals(request.getServletPath())) {
            chain.doFilter(request, response);
            return;
        }
        List<String> headers = Collections.list(request.getHeaders("Authorization"));
        if (headers.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        if (headers.size() != 1 || !headers.get(0).matches("(?i)Bearer [A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")) {
            responses.error(response, HttpStatus.UNAUTHORIZED, "관리자 인증 정보가 올바르지 않습니다.");
            return;
        }
        try {
            var principal = auth.authenticate(headers.get(0).substring(7));
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new UsernamePasswordAuthenticationToken(principal, null,
                    List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
            SecurityContextHolder.setContext(context);
        } catch (AdminException exception) {
            SecurityContextHolder.clearContext();
            responses.error(response, exception.status(), exception.getMessage());
            return;
        }
        chain.doFilter(request, response);
    }
}
