package com.orinan.adminapi.domain.auth;

import com.orinan.adminapi.domain.auth.business.AdminAuthBusiness;
import com.orinan.adminapi.domain.auth.controller.AdminAuthApiController;
import com.orinan.adminapi.domain.auth.controller.model.AdminLoginRequest;
import com.orinan.adminapi.domain.auth.controller.model.AdminLoginResponse;
import com.orinan.adminapi.domain.auth.controller.model.AdminMeResponse;
import com.orinan.adminapi.domain.auth.model.AdminPrincipal;

import com.orinan.adminapi.common.exception.AdminException;
import com.orinan.adminapi.config.security.AdminSecurityConfig;
import com.orinan.adminapi.config.security.AdminSecurityResponses;
import com.orinan.db.user.enums.UserRole;
import com.orinan.db.user.enums.UserStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.json.JsonMapper;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AdminSecurityTest {
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private AdminAuthBusiness auth;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(TestConfiguration.class, AdminSecurityConfig.class, AdminSecurityResponses.class);
        context.refresh();
        auth = context.getBean(AdminAuthBusiness.class);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        when(auth.authenticate("valid.admin.token")).thenReturn(new AdminPrincipal(3L, "admin@example.com"));
        when(auth.me(3L)).thenReturn(new AdminMeResponse(3L, "admin@example.com", "관리자",
                UserRole.ADMIN, UserStatus.REGISTERED));
    }

    @AfterEach
    void close() { context.close(); }

    @Test
    void anonymousAdminSwaggerAndUnknownRoutesCannotBypassAuthentication() throws Exception {
        for (String path : new String[]{"/admin-api/auth/me", "/admin-api/users", "/admin-api/unknown",
                "/swagger-ui/index.html", "/v3/api-docs", "/unknown"}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(header().string("Cache-Control", "no-store"));
        }
        verifyNoInteractions(auth);
    }

    @Test
    void validAdminPrincipalReachesControllerAndService() throws Exception {
        mvc.perform(get("/admin-api/auth/me").header("Authorization", "Bearer valid.admin.token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.body.id").value(3))
                .andExpect(header().string("Cache-Control", "no-store"));
        verify(auth).authenticate("valid.admin.token");
        verify(auth).me(3L);
    }

    @Test
    void malformedDuplicateAndQueryStringCredentialsAreRejected() throws Exception {
        for (String header : new String[]{"Basic token", "Bearer token", "Bearer  valid.admin.token",
                "Bearer valid.admin.token, Bearer second.admin.token"}) {
            mvc.perform(get("/admin-api/auth/me").header("Authorization", header)).andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/admin-api/auth/me").header("Authorization", "Bearer valid.admin.token", "Bearer valid.admin.token"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/admin-api/auth/me").param("access_token", "valid.admin.token"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(auth);
    }

    @Test
    void expiredAndDemotedSessionsReturnJson401And403() throws Exception {
        when(auth.authenticate(anyString())).thenThrow(new AdminException(HttpStatus.UNAUTHORIZED, "관리자 로그인이 필요합니다."));
        mvc.perform(get("/admin-api/auth/me").header("Authorization", "Bearer old.admin.token"))
                .andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", "Bearer"));
        doThrow(new AdminException(HttpStatus.FORBIDDEN, "관리자 권한이 필요합니다.")).when(auth).authenticate(anyString());
        mvc.perform(get("/admin-api/auth/me").header("Authorization", "Bearer old.admin.token"))
                .andExpect(status().isForbidden()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
        verify(auth, never()).me(anyLong());
    }

    @Test
    void loginIsOnlyPublicPostAndDoesNotRequireCsrfOrAcceptHttpBasic() throws Exception {
        when(auth.login(new AdminLoginRequest("admin@example.com", "correct"))).thenReturn(new AdminLoginResponse(
                "new.admin.token", "Bearer", java.time.Instant.parse("2026-09-28T01:00:00Z").plusSeconds(1800), 1800,
                new AdminMeResponse(3L, "admin@example.com", null, UserRole.ADMIN, UserStatus.REGISTERED)));
        mvc.perform(post("/admin-api/auth/login").servletPath("/admin-api/auth/login")
                        .header("Authorization", "Bearer expired.admin.token")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"admin@example.com\",\"password\":\"correct\"}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        verify(auth).login(new AdminLoginRequest("admin@example.com", "correct"));
        verify(auth, never()).authenticate(anyString());
        mvc.perform(get("/admin-api/auth/login")).andExpect(status().isUnauthorized());
        mvc.perform(post("/login").param("username", "user").param("password", "generated"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedAdminCannotUseUnapprovedRoutesAndCanLogout() throws Exception {
        mvc.perform(get("/unknown").header("Authorization", "Bearer valid.admin.token"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/admin-api/auth/logout").header("Authorization", "Bearer valid.admin.token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.body.success").value(true));
        verify(auth).logout(3L);
    }

    @Test
    void invalidLoginBodyNeverReachesPasswordCheck() throws Exception {
        mvc.perform(post("/admin-api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"invalid\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(auth);
    }

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    static class TestConfiguration {
        @Bean AdminAuthBusiness auth() { return mock(AdminAuthBusiness.class); }
        @Bean AdminAuthApiController controller(AdminAuthBusiness auth) { return new AdminAuthApiController(auth); }
        @Bean JsonMapper mapper() { return JsonMapper.builder().build(); }
    }
}
