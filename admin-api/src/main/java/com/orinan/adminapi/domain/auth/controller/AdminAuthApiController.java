package com.orinan.adminapi.domain.auth.controller;

import com.orinan.adminapi.common.api.Api;
import com.orinan.adminapi.domain.auth.business.AdminAuthBusiness;
import com.orinan.adminapi.domain.auth.controller.model.AdminLoginRequest;
import com.orinan.adminapi.domain.auth.controller.model.AdminLoginResponse;
import com.orinan.adminapi.domain.auth.controller.model.AdminLogoutResponse;
import com.orinan.adminapi.domain.auth.controller.model.AdminMeResponse;
import com.orinan.adminapi.domain.auth.model.AdminPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/admin-api/auth")
public class AdminAuthApiController {
    private final AdminAuthBusiness authBusiness;

    @PostMapping("/login")
    public Api<AdminLoginResponse> login(@Valid @RequestBody AdminLoginRequest request) {
        return Api.OK(authBusiness.login(request));
    }

    @GetMapping("/me")
    public Api<AdminMeResponse> me(@AuthenticationPrincipal AdminPrincipal principal) {
        return Api.OK(authBusiness.me(principal.id()));
    }

    @PostMapping("/logout")
    public Api<AdminLogoutResponse> logout(@AuthenticationPrincipal AdminPrincipal principal) {
        authBusiness.logout(principal.id());
        return Api.OK(new AdminLogoutResponse(true));
    }
}
