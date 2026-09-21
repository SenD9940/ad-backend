package com.orinan.api.domain.workspace.controller;

import com.orinan.api.annotation.UserSession;
import com.orinan.api.common.api.Api;
import com.orinan.api.domain.user.controller.model.UserResponse;
import com.orinan.api.domain.workspace.business.WorkspaceBusiness;
import com.orinan.api.domain.workspace.controller.model.WorkspaceRegisterRequest;
import com.orinan.api.domain.workspace.controller.model.WorkspaceResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/workspaces")
@RequiredArgsConstructor
public class WorkspaceApiController {

    private final WorkspaceBusiness workspaceBusiness;

    @GetMapping("/me")
    public Api<List<WorkspaceResponse>> getMyWorkspaces(@UserSession UserResponse user){
        var response = workspaceBusiness.getMyWorkspaces(user.getId());
        return Api.OK(response);
    }

    @PostMapping("/register")
    public Api<WorkspaceResponse> register(
            @UserSession UserResponse user,
            @RequestBody WorkspaceRegisterRequest request
    ){
        var response = workspaceBusiness.register(request, user.getId());
        return Api.OK(response);
    }
}
