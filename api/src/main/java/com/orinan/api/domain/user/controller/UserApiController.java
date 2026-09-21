package com.orinan.api.domain.user.controller;

import com.orinan.api.annotation.UserSession;
import com.orinan.api.common.api.Api;
import com.orinan.api.domain.user.controller.model.UserResponse;
import com.orinan.api.domain.user.controller.model.UserExistsRequest;
import com.orinan.api.domain.user.controller.model.UserLogoutRequest;
import com.orinan.api.domain.user.business.UserBusiness;
import com.orinan.api.domain.token.helper.AuthorizationTokens;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import com.orinan.api.domain.userprofile.business.UserProfileBusiness;
import com.orinan.api.domain.userprofile.controller.model.UserProfileMailNotificationUpdateRequest;
import com.orinan.api.domain.userprofile.controller.model.UserProfileResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserApiController {

    private final UserProfileBusiness userProfileBusiness;
    private final UserBusiness userBusiness;

    @PostMapping("/exists")
    public Api<Boolean> exists(@Valid @RequestBody UserExistsRequest request) {
        return Api.OK(userBusiness.existsByEmail(request.getEmail()));
    }

    @GetMapping("/me")
    public Api<UserResponse> me(@UserSession UserResponse user) {
        return Api.OK(userBusiness.me(user));
    }

    @PostMapping("/logout")
    public Api<Boolean> logout(
            @UserSession UserResponse user,
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody UserLogoutRequest request
    ) {
        return Api.OK(userBusiness.logout(user.getId(), AuthorizationTokens.extract(authorization), request.refreshToken()));
    }

    @GetMapping("/me/profile")
    public Api<UserProfileResponse> getMyProfile(
            @UserSession UserResponse user
    ) {
        return Api.OK(userProfileBusiness.getByUser(user));
    }

    @PatchMapping("/me/mail-notification")
    public Api<UserProfileResponse> updateMailNotification(
            @UserSession UserResponse user,
            @Valid @RequestBody UserProfileMailNotificationUpdateRequest request
    ) {
        return Api.OK(userProfileBusiness.updateMailNotification(user, request));
    }
}
