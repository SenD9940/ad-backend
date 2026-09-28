package com.orinan.adminapi.domain.platformconnection.converter;

import com.orinan.adminapi.annotation.Converter;
import com.orinan.adminapi.common.api.PageResponse;
import com.orinan.adminapi.domain.platformconnection.controller.model.*;
import com.orinan.db.platformconnection.projection.AdminPlatformConnectionProjection;
import org.springframework.data.domain.Page;

@Converter
public class AdminPlatformConnectionConverter {
    public AdminPlatformConnectionResponse toResponse(AdminPlatformConnectionProjection connection) {
        return new AdminPlatformConnectionResponse(connection.id(), connection.workspaceId(), connection.workspaceName(),
                connection.provider(), connection.externalAccountId(), connection.accountName(), connection.requiresReauth(),
                connection.assetCount(), connection.expiresAt(), connection.registeredAt(), connection.updatedAt());
    }
    public PageResponse<AdminPlatformConnectionResponse> toPage(Page<AdminPlatformConnectionProjection> page) {
        return PageResponse.of(page.getContent().stream().map(this::toResponse).toList(), page.getNumber(), page.getSize(), page.getTotalElements());
    }
    public AdminConnectionMutationResponse toMutation(long id, boolean changed) { return new AdminConnectionMutationResponse(id, changed); }
}
