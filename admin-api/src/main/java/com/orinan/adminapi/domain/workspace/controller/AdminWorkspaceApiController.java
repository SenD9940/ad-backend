package com.orinan.adminapi.domain.workspace.controller;

import com.orinan.adminapi.common.api.Api;
import com.orinan.adminapi.common.api.PageResponse;
import com.orinan.adminapi.domain.auth.model.AdminPrincipal;
import com.orinan.adminapi.domain.workspace.business.AdminWorkspaceBusiness;
import com.orinan.adminapi.domain.workspace.controller.model.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/admin-api/workspaces")
public class AdminWorkspaceApiController {
    private final AdminWorkspaceBusiness workspaceBusiness;

    @GetMapping
    public Api<PageResponse<AdminWorkspaceResponse>> workspaces(@RequestParam(required = false) String q,
            @RequestParam(required = false) Long ownerId, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Api.OK(workspaceBusiness.workspaces(q, ownerId, page, size));
    }
    @GetMapping("/{id}")
    public Api<AdminWorkspaceResponse> workspace(@PathVariable long id) { return Api.OK(workspaceBusiness.workspace(id)); }

    @GetMapping("/{id}/members")
    public Api<PageResponse<AdminWorkspaceMemberResponse>> members(@PathVariable long id,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return Api.OK(workspaceBusiness.members(id, page, size));
    }
    @GetMapping("/{id}/invitations")
    public Api<PageResponse<AdminWorkspaceInvitationResponse>> invitations(@PathVariable long id,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return Api.OK(workspaceBusiness.invitations(id, page, size));
    }
    @PatchMapping("/{id}")
    public Api<AdminWorkspaceMutationResponse> rename(@AuthenticationPrincipal AdminPrincipal actor, @PathVariable long id,
            @Valid @RequestBody AdminWorkspaceRenameRequest request) {
        return Api.OK(workspaceBusiness.rename(actor.id(), id, request));
    }
    @PatchMapping("/{id}/owner")
    public Api<AdminWorkspaceMutationResponse> transfer(@AuthenticationPrincipal AdminPrincipal actor, @PathVariable long id,
            @Valid @RequestBody AdminWorkspaceTransferRequest request) {
        return Api.OK(workspaceBusiness.transfer(actor.id(), id, request));
    }
    @PostMapping("/{id}/members/{userId}/remove")
    public Api<AdminWorkspaceMutationResponse> remove(@AuthenticationPrincipal AdminPrincipal actor, @PathVariable long id,
            @PathVariable long userId, @Valid @RequestBody AdminWorkspaceReasonRequest request) {
        return Api.OK(workspaceBusiness.removeMember(actor.id(), id, userId, request));
    }
    @PostMapping("/{id}/invitations/{userId}/revoke")
    public Api<AdminWorkspaceMutationResponse> revoke(@AuthenticationPrincipal AdminPrincipal actor, @PathVariable long id,
            @PathVariable long userId, @Valid @RequestBody AdminWorkspaceReasonRequest request) {
        return Api.OK(workspaceBusiness.revokeInvitation(actor.id(), id, userId, request));
    }
}
