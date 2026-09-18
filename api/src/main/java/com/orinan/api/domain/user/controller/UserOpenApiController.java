package com.orinan.api.domain.user.controller;

import com.orinan.api.common.api.Api;
import com.orinan.api.domain.token.controller.model.TokenResponse;
import com.orinan.api.domain.user.business.UserBusiness;
import com.orinan.api.domain.user.controller.model.UserLoginRequest;
import com.orinan.api.domain.user.controller.model.UserRegisterRequest;
import com.orinan.api.domain.user.controller.model.UserResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import com.orinan.api.domain.token.helper.AuthorizationTokens;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/open-api/users")
@RequiredArgsConstructor
public class UserOpenApiController {

    private final UserBusiness userBusiness;

    @PostMapping("/register")
    public Api<UserResponse> register(
            @Valid @RequestBody UserRegisterRequest request
    ){
        var response = userBusiness.register(request);
        return Api.OK(response);
    }

    @PostMapping("/login")
    public Api<TokenResponse> login(
            @Valid @RequestBody UserLoginRequest request
    ){
        var response = userBusiness.login(request);
        return Api.OK(response);
    }

    @RequestMapping(value = "/refresh", method = {RequestMethod.GET, RequestMethod.POST})
    public Api<TokenResponse> refreshToken(
            @RequestHeader(name = "Authorization", required = false) String refreshToken
    ){
        var response = userBusiness.refreshToken(AuthorizationTokens.extract(refreshToken));
        return Api.OK(response);
    }
}
