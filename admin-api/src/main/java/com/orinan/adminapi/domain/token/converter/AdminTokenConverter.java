package com.orinan.adminapi.domain.token.converter;

import com.orinan.adminapi.annotation.Converter;
import com.orinan.adminapi.domain.token.controller.model.AdminTokenResponse;
import com.orinan.adminapi.domain.token.model.AdminTokenDto;

import java.util.Objects;

@Converter
public class AdminTokenConverter {
    public AdminTokenResponse toResponse(AdminTokenDto token) {
        Objects.requireNonNull(token, "Admin token must not be null");
        return new AdminTokenResponse(token.token(), "Bearer", token.expiredAt(), token.expiresIn());
    }
}
