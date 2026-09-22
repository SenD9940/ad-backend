package com.orinan.api.domain.platformconnection.controller;

import com.orinan.api.annotation.UserSession;
import com.orinan.api.common.api.Api;
import com.orinan.api.domain.platformconnection.business.NaverConnectionBusiness;
import com.orinan.api.domain.platformconnection.controller.model.NaverChannelResponse;
import com.orinan.api.domain.platformconnection.controller.model.NaverChannelSelectRequest;
import com.orinan.api.domain.platformconnection.controller.model.NaverConnectRequest;
import com.orinan.api.domain.platformconnection.controller.model.PlatformConnectionResponse;
import com.orinan.api.domain.platformconnection.naver.NaverCommerceClient.Channel;
import com.orinan.api.domain.user.controller.model.UserResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/connections")
@RequiredArgsConstructor
public class NaverConnectionApiController {

    private final NaverConnectionBusiness business;

    @PostMapping("/naver")
    public Api<PlatformConnectionResponse> connect(
            @UserSession UserResponse user,
            @PathVariable Long workspaceId,
            @RequestBody @Valid NaverConnectRequest request
    ) {
        return Api.OK(business.connect(workspaceId, user.getId(), request));
    }

    @GetMapping("/{connectionId}/naver/channels")
    public Api<List<Channel>> getChannels(
            @UserSession UserResponse user,
            @PathVariable Long workspaceId,
            @PathVariable Long connectionId
    ) {
        return Api.OK(business.getChannels(workspaceId, connectionId, user.getId()));
    }

    @PostMapping("/{connectionId}/naver/channels")
    public Api<List<NaverChannelResponse>> selectChannels(
            @UserSession UserResponse user,
            @PathVariable Long workspaceId,
            @PathVariable Long connectionId,
            @RequestBody @Valid NaverChannelSelectRequest request
    ) {
        return Api.OK(business.selectChannels(workspaceId, connectionId, user.getId(), request));
    }
}
