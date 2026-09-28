package com.orinan.adminapi.domain.workspace.converter;

import com.orinan.adminapi.annotation.Converter;
import com.orinan.adminapi.common.api.PageResponse;
import com.orinan.adminapi.domain.workspace.controller.model.*;
import com.orinan.db.workspace.projection.*;
import org.springframework.data.domain.Page;
import java.time.LocalDateTime;

@Converter
public class AdminWorkspaceConverter {
    public AdminWorkspaceResponse toResponse(AdminWorkspaceProjection workspace) {
        return new AdminWorkspaceResponse(workspace.id(), workspace.name(), workspace.ownerId(), workspace.ownerEmail(),
                workspace.ownerStatus(), workspace.memberCount(), workspace.connectionCount(), workspace.registeredAt(), workspace.updatedAt());
    }
    public AdminWorkspaceMemberResponse toResponse(AdminWorkspaceMemberProjection member) {
        return new AdminWorkspaceMemberResponse(member.userId(), member.email(), member.name(), member.status(), member.owner(), member.registeredAt());
    }
    public AdminWorkspaceInvitationResponse toResponse(AdminWorkspaceInvitationProjection invitation, LocalDateTime now) {
        return new AdminWorkspaceInvitationResponse(invitation.userId(), invitation.email(), invitation.expiresAt(), !invitation.expiresAt().isAfter(now));
    }
    public PageResponse<AdminWorkspaceResponse> toWorkspacePage(Page<AdminWorkspaceProjection> page) {
        return PageResponse.of(page.getContent().stream().map(this::toResponse).toList(), page.getNumber(), page.getSize(), page.getTotalElements());
    }
    public PageResponse<AdminWorkspaceMemberResponse> toMemberPage(Page<AdminWorkspaceMemberProjection> page) {
        return PageResponse.of(page.getContent().stream().map(this::toResponse).toList(), page.getNumber(), page.getSize(), page.getTotalElements());
    }
    public PageResponse<AdminWorkspaceInvitationResponse> toInvitationPage(Page<AdminWorkspaceInvitationProjection> page, LocalDateTime now) {
        return PageResponse.of(page.getContent().stream().map(item -> toResponse(item, now)).toList(), page.getNumber(), page.getSize(), page.getTotalElements());
    }
    public AdminWorkspaceMutationResponse toMutation(long id, boolean changed) { return new AdminWorkspaceMutationResponse(id, changed); }
}
