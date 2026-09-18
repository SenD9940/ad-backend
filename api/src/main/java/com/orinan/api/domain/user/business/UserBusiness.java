package com.orinan.api.domain.user.business;

import com.orinan.api.annotation.Business;
import com.orinan.api.domain.token.business.TokenBusiness;
import com.orinan.api.domain.token.controller.model.TokenResponse;
import com.orinan.api.domain.user.controller.model.UserLoginRequest;
import com.orinan.api.domain.user.controller.model.UserRegisterRequest;
import com.orinan.api.domain.user.controller.model.UserResponse;
import com.orinan.api.domain.user.converter.UserConverter;
import com.orinan.api.domain.user.service.UserService;
import com.orinan.api.domain.userprofile.business.UserProfileBusiness;
import com.orinan.db.user.enums.UserRole;
import com.orinan.db.user.enums.UserStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Business
@RequiredArgsConstructor
public class UserBusiness{

    private final UserService userService;
    private final UserConverter userConverter;
    private final UserProfileBusiness userProfileBusiness;
    private final PasswordEncoder passwordEncoder;
    private final TokenBusiness tokenBusiness;

    @Transactional
    public UserResponse register(UserRegisterRequest request) {
        userService.validateRegistration(request.getEmail(), request.getPassword());
        var newUserProfileRegisterRequest = userConverter.toProfileRequest(request);

        var newEntity = userConverter.toEntity(request);
        newEntity.setRegisteredAt(LocalDateTime.now());
        newEntity.setStatus(UserStatus.REGISTERED);
        newEntity.setRole(UserRole.CUSTOMER);
        newEntity.setPassword(passwordEncoder.encode(request.getPassword()));
        var savedEntity = userService.save(newEntity);
        var userProfileResponse = userProfileBusiness.register(newUserProfileRegisterRequest, savedEntity);
        return userConverter.toResponse(savedEntity, userProfileResponse);
    }

    @Transactional
    public TokenResponse login(UserLoginRequest request) {
        var savedEntity = userService.authenticate(request.getEmail(), request.getPassword());
        userService.updateLastLogin(savedEntity);
        return tokenBusiness.issueToken(savedEntity);
    }

    public UserResponse me(UserResponse user) {
        user.setUserProfileResponse(userProfileBusiness.getByUser(user));
        return user;
    }

    public boolean logout(Long userId, String accessToken, String refreshToken) {
        return tokenBusiness.expireToken(userId, accessToken, refreshToken);
    }

    public TokenResponse refreshToken(String refreshToken) {
        return tokenBusiness.refreshTokenAndIssueNewTokenWithRefreshToken(refreshToken);
    }
}
