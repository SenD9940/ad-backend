package com.orinan.api.domain.platformconnection.naver.selftest;

import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.platformconnection.service.PlatformConnectionService;
import com.orinan.api.domain.user.exception.UserErrorCode;
import com.orinan.db.workspace.WorkspaceEntity;
import com.orinan.db.workspace.WorkspaceRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Component
@RequiredArgsConstructor
public class NaverSelfTestPolicy {
    private final NaverSelfTestProperties properties;
    private final Environment environment;
    private final PlatformConnectionService permissions;
    private final WorkspaceRepository workspaces;
    private final EntityManager entityManager;

    @Transactional(readOnly = true)
    public Availability availability(Long workspaceId, Long userId) {
        permissions.requireMember(workspaceId, userId);
        String reason = unavailableReason(workspaceId);
        if (reason != null) return new Availability(false, reason);
        var workspace = freshWorkspace(workspaceId);
        if (!owns(workspace, userId)) return new Availability(false, "워크스페이스 소유자만 내 스토어를 연결할 수 있습니다.");
        return new Availability(true, null);
    }

    @Transactional(readOnly = true)
    public Credentials requireConfiguredOwner(Long workspaceId, Long userId) {
        permissions.requireMember(workspaceId, userId);
        requireAvailable(workspaceId);
        requireOwner(freshWorkspace(workspaceId), userId);
        return snapshot();
    }

    /** Called after the remote reads, with the workspace refreshed under a write lock. */
    public void requireUnchanged(WorkspaceEntity workspace, Long userId, Credentials expected) {
        requireAvailable(workspace.getId());
        requireOwner(workspace, userId);
        if (!Objects.equals(snapshot(), expected)) {
            throw new ApiException(ApiCode.BAD_REQUEST, "네이버 앱 설정이 변경되었습니다. 내 스토어 연결을 다시 시도해 주세요.");
        }
    }

    private WorkspaceEntity freshWorkspace(Long workspaceId) {
        var workspace = workspaces.findById(workspaceId)
                .orElseThrow(() -> new ApiException(ApiCode.BAD_REQUEST, "존재하지 않는 워크스페이스입니다."));
        entityManager.refresh(workspace);
        return workspace;
    }

    private String unavailableReason(Long workspaceId) {
        if (!environment.acceptsProfiles(Profiles.of("local"))
                || environment.acceptsProfiles(Profiles.of("prod", "production"))) {
            return "내 스토어 테스트 연결은 로컬 환경에서만 사용할 수 있습니다.";
        }
        var config = properties.getSelfTest();
        if (config == null || !config.isEnabled()) return "내 스토어 테스트 연결이 설정되지 않았습니다.";
        if (config.getWorkspaceId() == null || config.getWorkspaceId() <= 0
                || !config.getWorkspaceId().equals(workspaceId)) return "내 스토어 테스트 대상으로 지정된 워크스페이스가 아닙니다.";
        if (properties.getAppId() == null || properties.getAppId().isBlank()
                || properties.getAppSecret() == null || properties.getAppSecret().isBlank()) {
            return "서버의 네이버 커머스 앱 정보를 설정해 주세요.";
        }
        return null;
    }

    private void requireAvailable(Long workspaceId) {
        String reason = unavailableReason(workspaceId);
        if (reason != null) throw new ApiException(ApiCode.BAD_REQUEST, reason);
    }

    private void requireOwner(WorkspaceEntity workspace, Long userId) {
        if (!owns(workspace, userId)) throw new ApiException(UserErrorCode.USER_PERMISSION_DENY);
    }

    private boolean owns(WorkspaceEntity workspace, Long userId) {
        return userId != null && workspace.getUser() != null && userId.equals(workspace.getUser().getId());
    }

    private Credentials snapshot() { return new Credentials(properties.getAppId(), properties.getAppSecret()); }

    public record Availability(boolean available, String reason) {}

    public record Credentials(String appId, String appSecret) {
        @Override public String toString() { return "SelfTestCredentials[REDACTED]"; }
    }
}
