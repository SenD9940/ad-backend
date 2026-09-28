package com.orinan.adminapi.domain.workspace.service;

import com.orinan.adminapi.common.exception.AdminException;
import com.orinan.db.workspace.AdminWorkspaceRepository;
import com.orinan.db.workspace.WorkspaceEntity;
import com.orinan.db.workspace.projection.*;
import com.orinan.db.workspaceinvitation.WorkspaceInvitationEntity;
import com.orinan.db.workspacemember.WorkspaceMemberEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AdminWorkspaceService {
    private final AdminWorkspaceRepository workspaceRepository;

    public Page<AdminWorkspaceProjection> search(String query, Long ownerId, Pageable pageable) {
        return workspaceRepository.search(query, ownerId, pageable);
    }
    public AdminWorkspaceProjection findSummaryWithThrow(long id) {
        return workspaceRepository.findSummary(id).orElseThrow(() -> missing("워크스페이스"));
    }
    public Page<AdminWorkspaceMemberProjection> findMembers(long id, Long ownerId, Pageable pageable) {
        return workspaceRepository.findMembers(id, ownerId, pageable);
    }
    public Page<AdminWorkspaceInvitationProjection> findInvitations(long id, Pageable pageable) {
        return workspaceRepository.findInvitations(id, pageable);
    }
    public WorkspaceEntity findByIdForUpdateWithThrow(long id) {
        return workspaceRepository.findByIdForUpdate(id).orElseThrow(() -> missing("워크스페이스"));
    }
    public boolean isMemberForUpdate(long workspaceId, long userId) {
        return workspaceRepository.findMemberForUpdate(workspaceId, userId).isPresent();
    }
    public WorkspaceMemberEntity findMemberForUpdateWithThrow(long workspaceId, long userId) {
        return workspaceRepository.findMemberForUpdate(workspaceId, userId).orElseThrow(() -> missing("워크스페이스 멤버"));
    }
    public void ensureMember(long workspaceId, long userId) { workspaceRepository.ensureMember(workspaceId, userId); }
    public void changeOwner(WorkspaceEntity workspace, long userId) { workspaceRepository.changeOwner(workspace, userId); }
    public void rename(WorkspaceEntity workspace, String name) { workspace.setName(name); }
    public void removeMember(WorkspaceMemberEntity member) { workspaceRepository.removeMember(member); }
    public WorkspaceInvitationEntity findInvitationForUpdateWithThrow(long workspaceId, long userId) {
        return workspaceRepository.findInvitationForUpdate(workspaceId, userId).orElseThrow(() -> missing("초대"));
    }
    public void removeInvitation(WorkspaceInvitationEntity invitation) { workspaceRepository.removeInvitation(invitation); }
    private AdminException missing(String name) {
        return new AdminException(HttpStatus.NOT_FOUND, name + " 정보를 찾을 수 없습니다.");
    }
}
