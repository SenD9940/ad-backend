package com.orinan.adminapi.domain.user.controller;

import com.orinan.adminapi.domain.auth.model.AdminPrincipal;
import com.orinan.adminapi.common.api.PageResponse;
import com.orinan.adminapi.common.api.Api;
import com.orinan.adminapi.domain.user.controller.model.*;
import com.orinan.adminapi.domain.user.business.AdminUserBusiness;
import com.orinan.db.user.enums.UserRole;
import com.orinan.db.user.enums.UserStatus;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin-api/users")
public class AdminUserApiController {
    private final AdminUserBusiness users;

    public AdminUserApiController(AdminUserBusiness users) {
        this.users = users;
    }

    @GetMapping
    public Api<PageResponse<AdminUserResponse>> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) UserStatus status,
            @RequestParam(required = false) UserRole role,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Api.OK(users.search(q, status, role, page, size));
    }

    @GetMapping("/{userId}")
    public Api<AdminUserResponse> detail(@PathVariable long userId) {
        return Api.OK(users.detail(userId));
    }

    @GetMapping("/{userId}/workspaces")
    public Api<PageResponse<AdminUserWorkspaceResponse>> workspaces(@PathVariable long userId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return Api.OK(users.workspaces(userId, page, size));
    }

    @PatchMapping("/{userId}/status")
    public Api<AdminUserMutationResponse> status(@AuthenticationPrincipal AdminPrincipal actor,
            @PathVariable long userId, @Valid @RequestBody AdminUserStatusRequest request) {
        return Api.OK(users.changeStatus(actor.id(), userId, request.status(), request.reason()));
    }

    @PatchMapping("/{userId}/role")
    public Api<AdminUserMutationResponse> role(@AuthenticationPrincipal AdminPrincipal actor,
            @PathVariable long userId, @Valid @RequestBody AdminUserRoleRequest request) {
        return Api.OK(users.changeRole(actor.id(), userId, request.role(), request.reason()));
    }

    @PostMapping("/{userId}/revoke-sessions")
    public Api<AdminUserMutationResponse> revokeSessions(@AuthenticationPrincipal AdminPrincipal actor,
            @PathVariable long userId, @Valid @RequestBody AdminUserRevokeSessionsRequest request) {
        return Api.OK(users.revokeSessions(actor.id(), userId, request.reason()));
    }
}
