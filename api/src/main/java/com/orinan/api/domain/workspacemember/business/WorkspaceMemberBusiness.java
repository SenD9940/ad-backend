package com.orinan.api.domain.workspacemember.business;

import com.orinan.api.annotation.Business;
import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.user.exception.UserErrorCode;
import com.orinan.api.domain.user.service.UserService;
import com.orinan.api.domain.workspace.service.WorkspaceService;
import com.orinan.api.domain.workspacemember.controller.model.WorkspaceMemberAcceptRequest;
import com.orinan.api.domain.workspacemember.controller.model.WorkspaceMemberInviteRequest;
import com.orinan.api.domain.workspacemember.controller.model.WorkspaceMemberInviteResponse;
import com.orinan.api.domain.workspacemember.controller.model.WorkspaceMemberKickRequest;
import com.orinan.api.domain.workspacemember.controller.model.WorkspaceMemberResponse;
import com.orinan.api.domain.workspacemember.converter.WorkspaceMemberConverter;
import com.orinan.api.domain.workspacemember.service.WorkspaceMemberService;
import com.orinan.api.domain.workspacemember.service.WorkspaceInvitationService;
import com.orinan.db.user.enums.UserStatus;
import com.orinan.db.workspacemember.WorkspaceMemberId;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.mail.MailException;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Business
@RequiredArgsConstructor
public class WorkspaceMemberBusiness {

    private final WorkspaceMemberService workspaceMemberService;
    private final WorkspaceMemberConverter workspaceMemberConverter;
    private final WorkspaceService workspaceService;
    private final UserService userService;
    private final WorkspaceInvitationService workspaceInvitationService;

    @Transactional(readOnly = true)
    public List<WorkspaceMemberResponse> getMembers(Long workspaceId, Long userId){
        var workspace = workspaceService.findByIdWithThrow(workspaceId);
        if (!workspace.getUser().getId().equals(userId)
                && !workspaceMemberService.exists(new WorkspaceMemberId(workspaceId, userId))) {
            throw new ApiException(UserErrorCode.USER_PERMISSION_DENY);
        }
        return workspaceMemberService.findAllByWorkspaceId(workspaceId).stream()
                .map(workspaceMemberConverter::toResponse)
                .toList();
    }

    public List<WorkspaceMemberInviteResponse> invite(WorkspaceMemberInviteRequest request, Long userId){
        var workspace = workspaceService.findByIdWithThrow(request.getWorkspaceId());
        if (!workspace.getUser().getId().equals(userId)) {
            throw new ApiException(UserErrorCode.USER_PERMISSION_DENY);
        }
        var results = new ArrayList<WorkspaceMemberInviteResponse>();
        var emails = request.getEmails().stream()
                .map(email -> email.strip().toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
        for (String email : emails) {
            try {
                var recipient = userService.findByEmailAndStatusWithThrow(email, UserStatus.REGISTERED);
                workspaceMemberService.validateNotMember(new WorkspaceMemberId(request.getWorkspaceId(), recipient.getId()));
                workspaceInvitationService.send(workspace, recipient);
                results.add(new WorkspaceMemberInviteResponse(email, true, "초대 메일을 발송했습니다"));
            } catch (ApiException e) {
                results.add(new WorkspaceMemberInviteResponse(email, false, e.getDescription()));
            } catch (MailException e) {
                results.add(new WorkspaceMemberInviteResponse(email, false, "초대 메일 발송에 실패했습니다"));
            } catch (DataAccessException | TransactionException e) {
                results.add(new WorkspaceMemberInviteResponse(email, false, "초대 저장에 실패했습니다"));
            }
        }
        return results;
    }

    @Transactional
    public WorkspaceMemberResponse accept(WorkspaceMemberAcceptRequest request, Long userId){
        var invitation = workspaceInvitationService.findValidWithThrow(request.getToken(), userId);
        workspaceService.findByIdWithThrow(invitation.getId().getWorkspaceId());
        var newEntity = workspaceMemberConverter.toEntity(invitation.getId());
        var savedEntity = workspaceMemberService.save(newEntity);
        workspaceInvitationService.delete(invitation);
        return workspaceMemberConverter.toResponse(savedEntity);
    }

    @Transactional
    public void kick(WorkspaceMemberKickRequest request, Long userId){
        var workspace = workspaceService.findByIdWithThrow(request.getWorkspaceId());
        if (!workspace.getUser().getId().equals(userId)) {
            throw new ApiException(UserErrorCode.USER_PERMISSION_DENY);
        }
        if (workspace.getUser().getId().equals(request.getUserId())) {
            throw new ApiException(ApiCode.BAD_REQUEST, "워크스페이스 소유자는 추방할 수 없습니다");
        }
        workspaceMemberService.delete(new WorkspaceMemberId(request.getWorkspaceId(), request.getUserId()));
    }
}
