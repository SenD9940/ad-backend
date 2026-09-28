package com.orinan.api.domain.platformconnection.naver.selftest;

import com.orinan.api.annotation.UserSession;
import com.orinan.api.common.api.Api;
import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.platformconnection.controller.model.PlatformConnectionResponse;
import com.orinan.api.domain.user.controller.model.UserResponse;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/connections/naver/self-test")
@RequiredArgsConstructor
public class NaverSelfTestController {
    private final NaverSelfTestBusiness business;

    @GetMapping
    public Api<NaverSelfTestPolicy.Availability> availability(@PathVariable Long workspaceId,
            @UserSession UserResponse user, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return Api.OK(business.availability(workspaceId, user.getId()));
    }

    @PostMapping
    public Api<PlatformConnectionResponse> connect(@PathVariable Long workspaceId,
            @UserSession UserResponse user, @RequestBody(required = false) JsonNode request,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        if (request != null && (!request.isObject() || !request.isEmpty())) {
            throw new ApiException(ApiCode.BAD_REQUEST, "내 스토어 연결에는 앱 정보나 판매자 정보를 전달할 수 없습니다.");
        }
        return Api.OK(business.connect(workspaceId, user.getId()));
    }
}
