package com.orinan.db.user.projection;

import com.orinan.db.user.enums.UserStatus;
import com.orinan.db.user.enums.UserRole;

public record AdminUserLockProjection(long id, String email, UserStatus status, UserRole role, long authVersion) {}
