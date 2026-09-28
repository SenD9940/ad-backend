package com.orinan.adminapi.domain.user.converter;

import com.orinan.adminapi.annotation.Converter;
import com.orinan.adminapi.common.api.PageResponse;
import com.orinan.adminapi.domain.user.controller.model.AdminUserMutationResponse;
import com.orinan.adminapi.domain.user.controller.model.AdminUserResponse;
import com.orinan.adminapi.domain.user.controller.model.AdminUserWorkspaceResponse;
import com.orinan.adminapi.domain.user.service.AdminUserMutationGuard.LockedUser;
import com.orinan.db.user.enums.UserRole;
import com.orinan.db.user.enums.UserStatus;
import com.orinan.db.user.projection.AdminUserSummaryProjection;
import com.orinan.db.user.projection.AdminUserWorkspaceProjection;
import org.springframework.data.domain.Page;

/** Only explicit, non-sensitive fields are mapped into administrator API responses. */
@Converter
public class AdminUserConverter {
    public AdminUserResponse toResponse(AdminUserSummaryProjection user) {
        return new AdminUserResponse(user.id(), user.email(), user.name(), user.status(), user.role(),
                user.lastLoginAt(), user.registeredAt(), user.updatedAt(), user.unRegisteredAt(),
                user.ownedWorkspaceCount(), user.workspaceCount());
    }

    public PageResponse<AdminUserResponse> toPage(Page<AdminUserSummaryProjection> users) {
        return PageResponse.of(users.getContent().stream().map(this::toResponse).toList(),
                users.getNumber(), users.getSize(), users.getTotalElements());
    }

    public AdminUserWorkspaceResponse toResponse(AdminUserWorkspaceProjection workspace) {
        return new AdminUserWorkspaceResponse(workspace.id(), workspace.name(), workspace.ownerId(),
                workspace.ownerEmail(), workspace.role(), workspace.registeredAt(), workspace.updatedAt());
    }

    public PageResponse<AdminUserWorkspaceResponse> toWorkspacePage(Page<AdminUserWorkspaceProjection> workspaces) {
        return PageResponse.of(workspaces.getContent().stream().map(this::toResponse).toList(),
                workspaces.getNumber(), workspaces.getSize(), workspaces.getTotalElements());
    }

    public AdminUserMutationResponse toMutationResponse(LockedUser user, boolean changed, int revokedSessions) {
        return toMutationResponse(user.id(), user.status(), user.role(), changed, revokedSessions);
    }

    public AdminUserMutationResponse toMutationResponse(long userId, UserStatus status, UserRole role,
                                                       boolean changed, int revokedSessions) {
        return new AdminUserMutationResponse(userId, status, role, changed, revokedSessions);
    }
}
