package com.orinan.db.workspace.projection;

import java.time.LocalDateTime;

public record AdminWorkspaceInvitationProjection(Long userId, String email, LocalDateTime expiresAt) {}
