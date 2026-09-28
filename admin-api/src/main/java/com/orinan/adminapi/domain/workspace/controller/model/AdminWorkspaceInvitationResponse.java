package com.orinan.adminapi.domain.workspace.controller.model;

import java.time.LocalDateTime;

public record AdminWorkspaceInvitationResponse(Long userId, String email, LocalDateTime expiresAt, boolean expired) {}
