package com.orinan.adminapi.domain.operations;

import com.orinan.adminapi.domain.auth.model.AdminPrincipal;
import com.orinan.adminapi.domain.platformconnection.business.AdminPlatformConnectionBusiness;
import com.orinan.adminapi.domain.platformconnection.controller.AdminPlatformConnectionApiController;
import com.orinan.adminapi.domain.platformconnection.controller.model.AdminConnectionReauthRequest;
import com.orinan.adminapi.domain.workspace.business.AdminWorkspaceBusiness;
import com.orinan.adminapi.domain.workspace.controller.AdminWorkspaceApiController;
import com.orinan.adminapi.domain.workspace.controller.model.AdminWorkspaceReasonRequest;
import com.orinan.adminapi.domain.workspace.controller.model.AdminWorkspaceRenameRequest;
import com.orinan.adminapi.domain.workspace.controller.model.AdminWorkspaceTransferRequest;
import com.orinan.adminapi.exceptionhandler.AdminExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminOperationMemoControllerTest {
    private final AdminWorkspaceBusiness workspaces = mock(AdminWorkspaceBusiness.class);
    private final AdminPlatformConnectionBusiness connections = mock(AdminPlatformConnectionBusiness.class);
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new AdminPrincipal(1, "admin@example.test"), null, List.of()));
        mvc = MockMvcBuilders.standaloneSetup(new AdminWorkspaceApiController(workspaces),
                        new AdminPlatformConnectionApiController(connections))
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
    void mutationRequestsAcceptOmittedNullAndBlankMemos() throws Exception {
        mvc.perform(patch("/admin-api/workspaces/10").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"새 이름\"}"))
                .andExpect(status().isOk());
        mvc.perform(patch("/admin-api/workspaces/10/owner").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expected_owner_id\":2,\"new_owner_id\":3,\"reason\":null}"))
                .andExpect(status().isOk());
        mvc.perform(post("/admin-api/workspaces/10/members/3/remove").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        mvc.perform(post("/admin-api/workspaces/10/invitations/3/revoke").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"  \"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/admin-api/connections/30/require-reauth").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());

        verify(workspaces).rename(1, 10, new AdminWorkspaceRenameRequest("새 이름", null));
        verify(workspaces).transfer(1, 10, new AdminWorkspaceTransferRequest(2L, 3L, null));
        verify(workspaces).removeMember(1, 10, 3, new AdminWorkspaceReasonRequest(null));
        verify(workspaces).revokeInvitation(1, 10, 3, new AdminWorkspaceReasonRequest("  "));
        verify(connections).requireReauth(1, 30, new AdminConnectionReauthRequest(null));
    }

    @Test
    void actualMutationValuesRemainRequiredAndMemosRemainBounded() throws Exception {
        for (String body : List.of("{}", "{\"name\":\"  \"}")) {
            mvc.perform(patch("/admin-api/workspaces/10").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        for (String body : List.of("{}", "{\"expected_owner_id\":2}", "{\"new_owner_id\":3}",
                "{\"expected_owner_id\":2,\"new_owner_id\":0}")) {
            mvc.perform(patch("/admin-api/workspaces/10/owner").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        String tooLong = "\"reason\":\"" + "x".repeat(501) + "\"";
        mvc.perform(patch("/admin-api/workspaces/10").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"새 이름\"," + tooLong + "}"))
                .andExpect(status().isBadRequest());
        mvc.perform(patch("/admin-api/workspaces/10/owner").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expected_owner_id\":2,\"new_owner_id\":3," + tooLong + "}"))
                .andExpect(status().isBadRequest());
        for (String path : List.of("/admin-api/workspaces/10/members/3/remove",
                "/admin-api/workspaces/10/invitations/3/revoke", "/admin-api/connections/30/require-reauth")) {
            mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{" + tooLong + "}"))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(workspaces, connections);
    }
}
