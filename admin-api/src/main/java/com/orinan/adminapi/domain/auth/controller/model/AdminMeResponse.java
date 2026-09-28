package com.orinan.adminapi.domain.auth.controller.model;

import com.orinan.db.user.enums.UserRole;
import com.orinan.db.user.enums.UserStatus;

public record AdminMeResponse(long id, String email, String name, UserRole role, UserStatus status) { }
