package com.orinan.adminapi.domain.platformconnection.service;

import com.orinan.adminapi.common.exception.AdminException;
import com.orinan.db.platformconnection.AdminPlatformConnectionRepository;
import com.orinan.db.platformconnection.PlatformConnectionEntity;
import com.orinan.db.platformconnection.enums.ProviderType;
import com.orinan.db.platformconnection.projection.AdminPlatformConnectionProjection;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AdminPlatformConnectionService {
    private final AdminPlatformConnectionRepository connectionRepository;

    public Page<AdminPlatformConnectionProjection> search(Long workspaceId, ProviderType provider, Boolean requiresReauth, Pageable pageable) {
        return connectionRepository.search(workspaceId, provider, requiresReauth, pageable);
    }
    public AdminPlatformConnectionProjection findSummaryWithThrow(long id) {
        return connectionRepository.findSummary(id).orElseThrow(this::missing);
    }
    public long findWorkspaceIdWithThrow(long id) {
        return connectionRepository.findWorkspaceId(id).orElseThrow(this::missing);
    }
    public PlatformConnectionEntity findByIdForUpdateWithThrow(long id) {
        return connectionRepository.findByIdForUpdate(id).orElseThrow(this::missing);
    }
    public void requireReauth(PlatformConnectionEntity connection) { connection.setRequiresReauth(true); }
    private AdminException missing() { return new AdminException(HttpStatus.NOT_FOUND, "플랫폼 연결 정보를 찾을 수 없습니다."); }
}
