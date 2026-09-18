package com.orinan.api.domain.user.controller.model;

import jakarta.validation.constraints.NotBlank;

public record UserLogoutRequest(@NotBlank String refreshToken) {}
