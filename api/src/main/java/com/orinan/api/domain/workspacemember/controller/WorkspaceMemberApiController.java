package com.orinan.api.domain.workspacemember.controller;

import com.orinan.api.annotation.UserSession;
import com.orinan.api.common.api.Api;
import com.orinan.api.domain.user.controller.model.UserResponse;
import com.orinan.api.domain.workspacemember.business.WorkspaceMemberBusiness;
import com.orinan.api.domain.workspacemember.controller.model.WorkspaceMemberAcceptRequest;
import com.orinan.api.domain.workspacemember.controller.model.WorkspaceMemberInviteRequest;
import com.orinan.api.domain.workspacemember.controller.model.WorkspaceMemberInviteResponse;
import com.orinan.api.domain.workspacemember.controller.model.WorkspaceMemberKickRequest;
import com.orinan.api.domain.workspacemember.controller.model.WorkspaceMemberResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/workspace-members")
@RequiredArgsConstructor
public class WorkspaceMemberApiController {

    private final WorkspaceMemberBusiness workspaceMemberBusiness;

    @PostMapping("/invite")
    public Api<List<WorkspaceMemberInviteResponse>> invite(
            @UserSession UserResponse user,
            @Valid @RequestBody WorkspaceMemberInviteRequest request
    ){
        var response = workspaceMemberBusiness.invite(request, user.getId());
        return Api.OK(response);
    }

    @PostMapping("/accept")
    public Api<WorkspaceMemberResponse> accept(
            @UserSession UserResponse user,
            @Valid @RequestBody WorkspaceMemberAcceptRequest request
    ){
        var response = workspaceMemberBusiness.accept(request, user.getId());
        return Api.OK(response);
    }

    @PostMapping("/kick")
    public Api<Boolean> kick(
            @UserSession UserResponse user,
            @Valid @RequestBody WorkspaceMemberKickRequest request
    ){
        workspaceMemberBusiness.kick(request, user.getId());
        return Api.OK(true);
    }
}
