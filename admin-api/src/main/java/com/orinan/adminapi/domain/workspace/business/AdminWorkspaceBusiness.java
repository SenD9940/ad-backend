package com.orinan.adminapi.domain.workspace.business;

import com.orinan.adminapi.annotation.Business;
import com.orinan.adminapi.common.api.AdminPageRequest;
import com.orinan.adminapi.common.api.PageResponse;
import com.orinan.adminapi.common.exception.AdminException;
import com.orinan.adminapi.domain.audit.service.AdminAuditService;
import com.orinan.adminapi.domain.user.service.AdminUserMutationGuard;
import com.orinan.adminapi.domain.workspace.controller.model.*;
import com.orinan.adminapi.domain.workspace.converter.AdminWorkspaceConverter;
import com.orinan.adminapi.domain.workspace.service.AdminWorkspaceService;
import com.orinan.db.user.enums.UserStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Business
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminWorkspaceBusiness {
    private final AdminWorkspaceService workspaceService;
    private final AdminWorkspaceConverter workspaceConverter;
    private final AdminUserMutationGuard guard;
    private final AdminAuditService audit;

    public PageResponse<AdminWorkspaceResponse> workspaces(String q, Long ownerId, int page, int size) {
        q = AdminPageRequest.query(q);
        if (ownerId != null) positive(ownerId);
        return workspaceConverter.toWorkspacePage(workspaceService.search(q, ownerId, AdminPageRequest.page(page, size)));
    }

    public AdminWorkspaceResponse workspace(long id) {
        positive(id);
        return workspaceConverter.toResponse(workspaceService.findSummaryWithThrow(id));
    }

    public PageResponse<AdminWorkspaceMemberResponse> members(long id, int page, int size) {
        positive(id);
        var workspace = workspaceService.findSummaryWithThrow(id);
        return workspaceConverter.toMemberPage(workspaceService.findMembers(id, workspace.ownerId(), AdminPageRequest.page(page, size)));
    }

    public PageResponse<AdminWorkspaceInvitationResponse> invitations(long id, int page, int size) {
        positive(id);
        workspaceService.findSummaryWithThrow(id);
        return workspaceConverter.toInvitationPage(workspaceService.findInvitations(id, AdminPageRequest.page(page, size)),
                LocalDateTime.now(ZoneId.of("Asia/Seoul")));
    }

    @Transactional
    public AdminWorkspaceMutationResponse rename(long actor, long id, AdminWorkspaceRenameRequest request) {
        guard.lock(actor, actor);
        positive(id);
        var workspace = workspaceService.findByIdForUpdateWithThrow(id);
        String name = request.name().strip();
        if (name.equals(workspace.getName())) return workspaceConverter.toMutation(id, false);
        audit.record(actor, "WORKSPACE_RENAME", "WORKSPACE", id, request.reason(), workspace.getName(), name);
        workspaceService.rename(workspace, name);
        return workspaceConverter.toMutation(id, true);
    }

    @Transactional
    public AdminWorkspaceMutationResponse transfer(long actor, long id, AdminWorkspaceTransferRequest request) {
        var locked = guard.lock(actor, request.newOwnerId());
        if (locked.target().status() != UserStatus.REGISTERED) throw conflict("활성 회원에게만 소유권을 이전할 수 있습니다.");
        positive(id);
        var workspace = workspaceService.findByIdForUpdateWithThrow(id);
        long oldOwner = workspace.getUser().getId();
        if (oldOwner != request.expectedOwnerId()) throw conflict("워크스페이스 소유자가 변경되었습니다. 최신 정보를 확인해 주세요.");
        if (oldOwner == request.newOwnerId()) return workspaceConverter.toMutation(id, false);
        if (!workspaceService.isMemberForUpdate(id, request.newOwnerId()))
            throw conflict("이미 참여 중인 워크스페이스 멤버에게 소유권을 이전해 주세요.");
        workspaceService.ensureMember(id, oldOwner);
        workspaceService.changeOwner(workspace, request.newOwnerId());
        audit.record(actor, "WORKSPACE_TRANSFER_OWNER", "WORKSPACE", id, request.reason(), Long.toString(oldOwner), request.newOwnerId().toString());
        return workspaceConverter.toMutation(id, true);
    }

    @Transactional
    public AdminWorkspaceMutationResponse removeMember(long actor, long id, long memberId, AdminWorkspaceReasonRequest request) {
        positive(memberId);
        guard.lock(actor, actor);
        positive(id);
        var workspace = workspaceService.findByIdForUpdateWithThrow(id);
        if (workspace.getUser().getId() == memberId) throw conflict("소유자는 멤버에서 제거할 수 없습니다. 소유권을 먼저 이전해 주세요.");
        var member = workspaceService.findMemberForUpdateWithThrow(id, memberId);
        workspaceService.removeMember(member);
        audit.record(actor, "WORKSPACE_REMOVE_MEMBER", "WORKSPACE", id, request.reason(), "member:" + memberId, null);
        return workspaceConverter.toMutation(id, true);
    }

    @Transactional
    public AdminWorkspaceMutationResponse revokeInvitation(long actor, long id, long userId, AdminWorkspaceReasonRequest request) {
        positive(userId);
        guard.lock(actor, actor);
        positive(id);
        workspaceService.findByIdForUpdateWithThrow(id);
        var invitation = workspaceService.findInvitationForUpdateWithThrow(id, userId);
        workspaceService.removeInvitation(invitation);
        audit.record(actor, "WORKSPACE_REVOKE_INVITATION", "WORKSPACE", id, request.reason(), "invited_user:" + userId, null);
        return workspaceConverter.toMutation(id, true);
    }

    private void positive(long id) { if (id < 1) throw new AdminException(HttpStatus.BAD_REQUEST, "올바른 ID를 입력해 주세요."); }
    private AdminException conflict(String message) { return new AdminException(HttpStatus.CONFLICT, message); }
}
