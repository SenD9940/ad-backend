package com.orinan.adminapi.domain.overview.converter;

import com.orinan.adminapi.annotation.Converter;
import com.orinan.adminapi.domain.overview.controller.model.AdminOverviewResponse;
import com.orinan.db.adminoverview.projection.AdminOverviewProjection;
import java.time.LocalDateTime;

@Converter
public class AdminOverviewConverter {
    public AdminOverviewResponse toResponse(AdminOverviewProjection summary, LocalDateTime fetchedAt) {
        return new AdminOverviewResponse(summary.users(), summary.registeredUsers(), summary.suspendedUsers(),
                summary.unregisteredUsers(), summary.activeAdministrators(), summary.newUsersLast7Days(),
                summary.workspaces(), summary.connections(), summary.connectionsRequiringReauth(),
                summary.assets(), summary.pendingInvitations(), fetchedAt);
    }
}
