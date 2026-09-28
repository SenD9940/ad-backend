package com.orinan.adminapi.domain.overview.controller.model;

import java.time.LocalDateTime;

public record AdminOverviewResponse(long users, long registeredUsers, long suspendedUsers, long unregisteredUsers,
        long activeAdministrators, long newUsersLast7Days, long workspaces, long connections,
        long connectionsRequiringReauth, long assets, long pendingInvitations, LocalDateTime fetchedAt) {}
