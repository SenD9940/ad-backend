package com.orinan.adminapi.domain.user.controller.model;

import com.orinan.db.user.enums.UserStatus;
import com.orinan.db.user.enums.UserRole;

public record AdminUserMutationResponse(long id, UserStatus status, UserRole role, boolean changed,
                                        int revokedSessions) {}
