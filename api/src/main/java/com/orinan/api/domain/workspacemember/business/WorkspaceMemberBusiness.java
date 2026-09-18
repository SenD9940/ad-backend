package com.orinan.api.domain.workspacemember.business;

import com.orinan.api.annotation.Business;
import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.user.exception.UserErrorCode;
import com.orinan.api.domain.user.service.UserService;
import com.orinan.api.domain.workspace.service.WorkspaceService;
import com.orinan.api.domain.workspacemember.controller.model.WorkspaceMemberInviteRequest;
import com.orinan.api.domain.workspacemember.controller.model.WorkspaceMemberResponse;
import com.orinan.api.domain.workspacemember.converter.WorkspaceMemberConverter;
import com.orinan.api.domain.workspacemember.service.WorkspaceMemberService;
import com.orinan.db.user.enums.UserStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;

@Business
@RequiredArgsConstructor
public class WorkspaceMemberBusiness {

    private final WorkspaceMemberService workspaceMemberService;
    private final WorkspaceMemberConverter workspaceMemberConverter;
    private final WorkspaceService workspaceService;
    private final UserService userService;

    @Transactional
    public WorkspaceMemberResponse invite(WorkspaceMemberInviteRequest request, Long userId){
        var workspace = workspaceService.findByIdWithThrow(request.getWorkspaceId());
        if (!workspace.getUser().getId().equals(userId)) {
            throw new ApiException(UserErrorCode.USER_PERMISSION_DENY);
        }
        userService.findByIdAndStatusWithThrow(request.getUserId(), UserStatus.REGISTERED);
        var newEntity = workspaceMemberConverter.toEntity(request);
        var savedEntity = workspaceMemberService.save(newEntity);
        return workspaceMemberConverter.toResponse(savedEntity);
    }
}
