package com.orinan.adminapi.domain.platformconnection.controller;

import com.orinan.adminapi.common.api.Api;
import com.orinan.adminapi.common.api.PageResponse;
import com.orinan.adminapi.domain.auth.model.AdminPrincipal;
import com.orinan.adminapi.domain.platformconnection.business.AdminPlatformConnectionBusiness;
import com.orinan.adminapi.domain.platformconnection.controller.model.*;
import com.orinan.db.platformconnection.enums.ProviderType;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/admin-api/connections")
public class AdminPlatformConnectionApiController {
    private final AdminPlatformConnectionBusiness connectionBusiness;

    @GetMapping
    public Api<PageResponse<AdminPlatformConnectionResponse>> connections(@RequestParam(required = false) Long workspaceId,
            @RequestParam(required = false) ProviderType provider, @RequestParam(required = false) Boolean requiresReauth,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return Api.OK(connectionBusiness.connections(workspaceId, provider, requiresReauth, page, size));
    }
    @GetMapping("/{id}")
    public Api<AdminPlatformConnectionResponse> connection(@PathVariable long id) { return Api.OK(connectionBusiness.connection(id)); }

    @PostMapping("/{id}/require-reauth")
    public Api<AdminConnectionMutationResponse> reauth(@AuthenticationPrincipal AdminPrincipal actor, @PathVariable long id,
            @Valid @RequestBody AdminConnectionReauthRequest request) {
        return Api.OK(connectionBusiness.requireReauth(actor.id(), id, request));
    }
}
