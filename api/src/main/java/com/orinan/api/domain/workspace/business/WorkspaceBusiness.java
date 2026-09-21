package com.orinan.api.domain.workspace.business;

import com.orinan.api.annotation.Business;
import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.user.exception.UserErrorCode;
import com.orinan.api.domain.user.service.UserService;
import com.orinan.api.domain.workspace.controller.model.WorkspaceRegisterRequest;
import com.orinan.api.domain.workspace.controller.model.WorkspaceResponse;
import com.orinan.api.domain.workspace.converter.WorkspaceConverter;
import com.orinan.api.domain.workspace.service.WorkspaceService;
import com.orinan.api.domain.workspacemember.service.WorkspaceMemberService;
import com.orinan.db.user.enums.UserStatus;
import com.orinan.db.workspacemember.WorkspaceMemberEntity;
import com.orinan.db.workspacemember.WorkspaceMemberId;
import com.orinan.db.workspacemember.enums.WorkspaceMemberRole;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Business
@RequiredArgsConstructor
public class WorkspaceBusiness {

    private final WorkspaceService workspaceService;
    private final WorkspaceConverter workspaceConverter;
    private final UserService userService;
    private final WorkspaceMemberService workspaceMemberService;

    @Transactional(readOnly = true)
    public WorkspaceResponse getMyWorkspace(Long workspaceId, Long userId){
        var workspace = workspaceService.findByIdWithThrow(workspaceId);
        if (!workspace.getUser().getId().equals(userId)) {
            throw new ApiException(UserErrorCode.USER_PERMISSION_DENY);
        }
        return workspaceConverter.toResponse(workspace);
    }

    @Transactional(readOnly = true)
    public List<WorkspaceResponse> getMyWorkspaces(Long userId){
        return workspaceService.findAllByUserId(userId).stream()
                .map(workspaceConverter::toResponse)
                .toList();
    }

    @Transactional
    public WorkspaceResponse register(WorkspaceRegisterRequest request, Long userId){
        var newEntity = workspaceConverter.toEntity(request);
        var savedUserEntity = userService.findByIdAndStatusWithThrow(userId, UserStatus.REGISTERED);
        newEntity.setUser(savedUserEntity);
        var savedEntity = workspaceService.save(newEntity);
        var memberEntity = WorkspaceMemberEntity.builder()
                .id(new WorkspaceMemberId(savedEntity.getId(), savedUserEntity.getId()))
                .role(WorkspaceMemberRole.MEMBER)
                .build();
        workspaceMemberService.save(memberEntity);
        return workspaceConverter.toResponse(savedEntity);
    }

}
