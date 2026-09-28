package com.orinan.adminapi.domain.workspace.controller.model;

import java.time.LocalDateTime;
import com.orinan.db.user.enums.UserStatus;

public record AdminWorkspaceMemberResponse(Long userId, String email, String name, UserStatus status, boolean owner, LocalDateTime registeredAt) {}
