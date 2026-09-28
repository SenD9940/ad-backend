package com.orinan.db.adminoverview.projection;



public record AdminOverviewProjection(long users, long registeredUsers, long suspendedUsers, long unregisteredUsers,
        long activeAdministrators, long newUsersLast7Days, long workspaces, long connections,
        long connectionsRequiringReauth, long assets, long pendingInvitations) {}
