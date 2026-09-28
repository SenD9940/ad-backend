package com.orinan.adminapi.domain.platformconnection.business;

import com.orinan.adminapi.annotation.Business;
import com.orinan.adminapi.common.api.AdminPageRequest;
import com.orinan.adminapi.common.api.PageResponse;
import com.orinan.adminapi.common.exception.AdminException;
import com.orinan.adminapi.domain.audit.service.AdminAuditService;
import com.orinan.adminapi.domain.platformconnection.controller.model.*;
import com.orinan.adminapi.domain.platformconnection.converter.AdminPlatformConnectionConverter;
import com.orinan.adminapi.domain.platformconnection.service.AdminPlatformConnectionService;
import com.orinan.adminapi.domain.user.service.AdminUserMutationGuard;
import com.orinan.adminapi.domain.workspace.service.AdminWorkspaceService;
import com.orinan.db.platformconnection.enums.ProviderType;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import java.util.Objects;

@Business
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminPlatformConnectionBusiness {
    private final AdminPlatformConnectionService connectionService;
    private final AdminPlatformConnectionConverter connectionConverter;
    private final AdminWorkspaceService workspaceService;
    private final AdminUserMutationGuard guard;
    private final AdminAuditService audit;

    public PageResponse<AdminPlatformConnectionResponse> connections(Long workspaceId, ProviderType provider,
            Boolean requiresReauth, int page, int size) {
        if (workspaceId != null) positive(workspaceId);
        return connectionConverter.toPage(connectionService.search(workspaceId, provider, requiresReauth, AdminPageRequest.page(page, size)));
    }
    public AdminPlatformConnectionResponse connection(long id) {
        positive(id);
        return connectionConverter.toResponse(connectionService.findSummaryWithThrow(id));
    }

    @Transactional
    public AdminConnectionMutationResponse requireReauth(long actor, long id, AdminConnectionReauthRequest request) {
        positive(id);
        guard.lock(actor, actor);
        // Keep workspace -> connection locking consistent with the normal service's asset updates.
        long workspaceId = connectionService.findWorkspaceIdWithThrow(id);
        workspaceService.findByIdForUpdateWithThrow(workspaceId);
        var connection = connectionService.findByIdForUpdateWithThrow(id);
        if (!Objects.equals(connection.getWorkspace().getId(), workspaceId))
            throw new AdminException(HttpStatus.NOT_FOUND, "플랫폼 연결 정보를 찾을 수 없습니다.");
        if (Boolean.TRUE.equals(connection.getRequiresReauth())) return connectionConverter.toMutation(id, false);
        connectionService.requireReauth(connection);
        audit.record(actor, "CONNECTION_REQUIRE_REAUTH", "CONNECTION", id, request.reason(), "false", "true");
        return connectionConverter.toMutation(id, true);
    }

    private void positive(long id) { if (id < 1) throw new AdminException(HttpStatus.BAD_REQUEST, "올바른 ID를 입력해 주세요."); }
}
