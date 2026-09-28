package com.orinan.adminapi.domain.auth.converter;

import com.orinan.adminapi.annotation.Converter;
import com.orinan.adminapi.domain.auth.controller.model.AdminLoginResponse;
import com.orinan.adminapi.domain.auth.controller.model.AdminMeResponse;
import com.orinan.adminapi.domain.auth.model.AdminPrincipal;
import com.orinan.adminapi.domain.token.controller.model.AdminTokenResponse;
import com.orinan.db.user.UserEntity;

@Converter
public class AdminAuthConverter {
    public AdminMeResponse toResponse(UserEntity user, String displayName) {
        return new AdminMeResponse(user.getId(), user.getEmail(), displayName, user.getRole(), user.getStatus());
    }

    public AdminLoginResponse toLoginResponse(AdminTokenResponse token, AdminMeResponse user) {
        return new AdminLoginResponse(token.accessToken(), token.tokenType(), token.expiresAt(), token.expiresIn(), user);
    }

    public AdminPrincipal toPrincipal(UserEntity user) {
        return new AdminPrincipal(user.getId(), user.getEmail());
    }
}
