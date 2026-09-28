package com.orinan.db.workspace.projection;

import java.time.LocalDateTime;
import com.orinan.db.user.enums.UserStatus;

public record AdminWorkspaceMemberProjection(Long userId, String email, String name, UserStatus status, boolean owner, LocalDateTime registeredAt) {}
