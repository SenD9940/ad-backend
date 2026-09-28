package com.orinan.adminapi.domain.user.controller;

import com.orinan.adminapi.domain.user.business.AdminUserBusiness;
import com.orinan.adminapi.domain.user.controller.model.AdminUserMutationResponse;
import com.orinan.adminapi.domain.user.controller.model.AdminUserResponse;

import com.orinan.adminapi.domain.auth.model.AdminPrincipal;
import com.orinan.adminapi.common.exception.AdminException;
import com.orinan.adminapi.exceptionhandler.AdminExceptionHandler;
import com.orinan.adminapi.common.api.PageResponse;
import com.orinan.db.user.enums.UserRole;
import com.orinan.db.user.enums.UserStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Wire validation and safe DTO coverage; the shared security-chain tests cover route authorization. */
class AdminUserApiControllerTest {
    private final AdminUserBusiness business = mock(AdminUserBusiness.class);
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new AdminPrincipal(1, "admin@example.test"), null, List.of()));
        mvc = MockMvcBuilders.standaloneSetup(new AdminUserApiController(business))
                .setControllerAdvice(new AdminExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setMessageConverters(new JacksonJsonHttpMessageConverter(JsonMapper.builder()
                        .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build()))
                .build();
    }

    @AfterEach
    void clearSecurity() { SecurityContextHolder.clearContext(); }

    @Test
    void statusRoleAndRevokeUseAuthenticatedActorAndReturnSnakeCaseResult() throws Exception {
        when(business.changeStatus(1, 3, UserStatus.SUSPENDED, "사유"))
                .thenReturn(new AdminUserMutationResponse(3, UserStatus.SUSPENDED, UserRole.CUSTOMER, true, 2));
        mvc.perform(patch("/admin-api/users/3/status").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SUSPENDED\",\"reason\":\"사유\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.result_code").value(200))
                .andExpect(jsonPath("$.body.revoked_sessions").value(2));
        mvc.perform(patch("/admin-api/users/3/role").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ADMIN\",\"reason\":\"사유\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/admin-api/users/3/revoke-sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"사유\"}"))
                .andExpect(status().isOk());
        verify(business).changeStatus(1, 3, UserStatus.SUSPENDED, "사유");
        verify(business).changeRole(1, 3, UserRole.ADMIN, "사유");
        verify(business).revokeSessions(1, 3, "사유");
    }

    @Test
    void rejectsMissingUnknownOrOverlongMutationInputBeforeCallingBusiness() throws Exception {
        for (String body : List.of("{}",
                "{\"status\":null,\"reason\":\"사유\"}",
                "{\"status\":\"UNKNOWN\",\"reason\":\"사유\"}",
                "{\"status\":\"SUSPENDED\",\"reason\":\"사유\",\"actor_id\":2}",
                "{\"status\":\"SUSPENDED\",\"reason\":\"" + "x".repeat(501) + "\"}")) {
            mvc.perform(patch("/admin-api/users/3/status").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.result.result_code").value(400));
        }
        mvc.perform(patch("/admin-api/users/3/role").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"사유\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(business);
    }

    @Test
    void omittedNullAndBlankMemosAreOptionalWhileActionValuesRemainRequired() throws Exception {
        mvc.perform(patch("/admin-api/users/3/status").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SUSPENDED\"}"))
                .andExpect(status().isOk());
        mvc.perform(patch("/admin-api/users/3/role").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ADMIN\",\"reason\":null}"))
                .andExpect(status().isOk());
        mvc.perform(post("/admin-api/users/3/revoke-sessions").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        mvc.perform(post("/admin-api/users/3/revoke-sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"  \"}"))
                .andExpect(status().isOk());

        verify(business).changeStatus(1, 3, UserStatus.SUSPENDED, null);
        verify(business).changeRole(1, 3, UserRole.ADMIN, null);
        verify(business).revokeSessions(1, 3, null);
        verify(business).revokeSessions(1, 3, "  ");
    }

    @Test
    void safeSearchAndWorkspaceResponsesContainNoAccountCredentials() throws Exception {
        var summary = new AdminUserResponse(3, "customer@example.test", "고객", UserStatus.REGISTERED,
                UserRole.CUSTOMER, null, null, null, null, 1, 2);
        when(business.search("고객", UserStatus.REGISTERED, UserRole.CUSTOMER, 2, 10))
                .thenReturn(new PageResponse<>(List.of(summary), 2, 10, 21, 3));
        String response = mvc.perform(get("/admin-api/users").param("q", "고객")
                        .param("status", "REGISTERED").param("role", "CUSTOMER").param("page", "2").param("size", "10"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.body.total_elements").value(21))
                .andExpect(jsonPath("$.body.items[0].workspace_count").value(2)).andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("password", "phone", "address", "token", "auth_version");
        when(business.detail(3)).thenReturn(summary);
        mvc.perform(get("/admin-api/users/3")).andExpect(status().isOk()).andExpect(jsonPath("$.body.id").value(3));
        mvc.perform(get("/admin-api/users/3/workspaces")).andExpect(status().isOk());
        verify(business).workspaces(3, 0, 20);
    }

    @Test
    void reportsBusinessConflictsAndInvalidEnumsWithoutStackTraces() throws Exception {
        when(business.changeRole(1, 1, UserRole.CUSTOMER, "사유"))
                .thenThrow(new AdminException(HttpStatus.CONFLICT, "자신의 관리자 권한은 해제할 수 없습니다."));
        String body = mvc.perform(patch("/admin-api/users/1/role").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"CUSTOMER\",\"reason\":\"사유\"}"))
                .andExpect(status().isConflict()).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("Exception", "stack", "com.orinan");
        mvc.perform(get("/admin-api/users").param("status", "BAD")).andExpect(status().isBadRequest());
        mvc.perform(get("/admin-api/users").param("page", "bad")).andExpect(status().isBadRequest());
    }
}
