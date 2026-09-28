package com.orinan.api.domain.support;

import com.orinan.api.domain.support.interceptor.SupportAccessInterceptor;
import com.orinan.api.domain.support.model.SupportContext;
import com.orinan.api.domain.support.service.*;
import com.orinan.api.exceptionhandler.ApiExceptionHandler;
import com.orinan.api.exceptionhandler.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SupportAccessInterceptorTest {
    private static final String TOKEN = "a".repeat(43);
    private final SupportSessionService sessions = mock(SupportSessionService.class);
    private final SupportActionService actions = mock(SupportActionService.class);
    private final Runnable mutation = mock(Runnable.class);
    private final Fixture controller = new Fixture(mutation);
    private MockMvc mvc;

    @BeforeEach void setup() {
        when(sessions.authenticate(TOKEN)).thenReturn(context("OPERATE"));
        when(actions.begin(any(), anyString(), anyString())).thenReturn(88L);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(new SupportAccessInterceptor(sessions, new SupportRoutePolicy(), actions))
                .setControllerAdvice(new ApiExceptionHandler(), new GlobalExceptionHandler()).build();
    }

    @Test void auditIntentIsCommittedBeforeAnAdmittedMutation() throws Exception {
        mvc.perform(patch("/api/workspaces/10/meta/ad-accounts/30/ads/100").header("X-Support-Token", TOKEN))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        var order = inOrder(actions, mutation);
        order.verify(actions).begin(eq(context("OPERATE")), eq("PATCH"), eq("/api/workspaces/10/meta/ad-accounts/30/ads/100"));
        order.verify(mutation).run(); order.verify(actions).complete(88L, 200);
    }

    @Test void failedAuditBlocksWritesAndNeverIncludesTheSecretInErrors() throws Exception {
        when(actions.begin(any(), anyString(), anyString())).thenThrow(new IllegalStateException(TOKEN));
        var result = mvc.perform(patch("/api/workspaces/10/meta/ad-accounts/30/ads/100").header("X-Support-Token", TOKEN))
                .andExpect(status().isServiceUnavailable()).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(TOKEN);
        verifyNoInteractions(mutation);
    }

    @Test void deniesPublicRoutesEvenWithAValidSupportSecretAndNormalAuthorization() throws Exception {
        mvc.perform(post("/open-api/users/login").header("X-Support-Token", TOKEN).header("Authorization", "Bearer ordinary"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(mutation);
        verify(actions).complete(88L, 403);
    }

    @Test void ordinaryPublicRequestsRetainTheirExistingBehavior() throws Exception {
        mvc.perform(post("/open-api/users/login")).andExpect(status().isOk());
        verify(mutation).run(); verifyNoInteractions(sessions, actions);
    }

    @Test void duplicateMalformedAndCommaCombinedHeadersCannotReachTheController() throws Exception {
        mvc.perform(post("/open-api/users/login").header("X-Support-Token", TOKEN, TOKEN)).andExpect(status().isUnauthorized());
        mvc.perform(post("/open-api/users/login").header("X-Support-Token", TOKEN + "," + TOKEN)).andExpect(status().isUnauthorized());
        mvc.perform(post("/open-api/users/login").header("X-Support-Token", "")).andExpect(status().isUnauthorized());
        verifyNoInteractions(mutation, sessions, actions);
    }

    @Test void readOnlyAndCrossWorkspaceGrantsCannotMutate() throws Exception {
        when(sessions.authenticate(TOKEN)).thenReturn(context("READ_ONLY"));
        mvc.perform(patch("/api/workspaces/10/meta/ad-accounts/30/ads/100").header("X-Support-Token", TOKEN))
                .andExpect(status().isForbidden());
        when(sessions.authenticate(TOKEN)).thenReturn(context("OPERATE"));
        mvc.perform(patch("/api/workspaces/11/meta/ad-accounts/30/ads/100").header("X-Support-Token", TOKEN))
                .andExpect(status().isForbidden());
        verifyNoInteractions(mutation);
    }

    @Test void unknownNewRoutesAreNotInheritedPermissions() throws Exception {
        mvc.perform(get("/api/workspaces/10/new-feature").header("X-Support-Token", TOKEN)).andExpect(status().isForbidden());
        verifyNoInteractions(mutation);
    }

    @Test void auditCompletionFailureDoesNotTurnSuccessfulWriteIntoARetryableError() throws Exception {
        doThrow(new IllegalStateException()).when(actions).complete(88L, 200);
        mvc.perform(patch("/api/workspaces/10/meta/ad-accounts/30/ads/100").header("X-Support-Token", TOKEN))
                .andExpect(status().isOk());
        verify(mutation, times(1)).run();
    }

    private SupportContext context(String mode) {
        return new SupportContext(1, 2, 10, 3, 4, mode, LocalDateTime.of(2030, 1, 1, 0, 0));
    }
    @RestController static class Fixture {
        private final Runnable mutation;
        Fixture(Runnable mutation) { this.mutation = mutation; }
        @PatchMapping("/api/workspaces/{workspaceId}/meta/ad-accounts/{assetId}/ads/{adId}")
        Map<String, Object> patch() { mutation.run(); return Map.of("ok", true); }
        @PostMapping("/open-api/users/login") Map<String, Object> login() { mutation.run(); return Map.of("ok", true); }
        @GetMapping("/api/workspaces/{workspaceId}/new-feature") Map<String, Object> newFeature() { mutation.run(); return Map.of("ok", true); }
    }
}
