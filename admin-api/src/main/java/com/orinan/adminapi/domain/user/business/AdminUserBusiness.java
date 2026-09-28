package com.orinan.adminapi.domain.user.business;

import com.orinan.adminapi.domain.audit.service.AdminAuditService;
import com.orinan.adminapi.common.exception.AdminException;
import com.orinan.adminapi.common.api.PageResponse;
import com.orinan.adminapi.common.api.AdminPageRequest;
import com.orinan.adminapi.domain.user.controller.model.AdminUserMutationResponse;
import com.orinan.adminapi.domain.user.controller.model.AdminUserResponse;
import com.orinan.adminapi.domain.user.controller.model.AdminUserWorkspaceResponse;
import com.orinan.adminapi.domain.user.service.AdminUserMutationGuard.LockedUser;
import com.orinan.db.user.enums.UserRole;
import com.orinan.db.user.enums.UserStatus;
import org.springframework.http.HttpStatus;
import com.orinan.adminapi.annotation.Business;
import com.orinan.adminapi.domain.user.converter.AdminUserConverter;
import com.orinan.adminapi.domain.user.service.AdminUserService;
import com.orinan.adminapi.domain.user.service.AdminUserMutationGuard;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;

@Business
@Transactional(readOnly = true)
public class AdminUserBusiness {
    private final AdminUserService users;
    private final AdminUserConverter converter;
    private final AdminUserMutationGuard guard;
    private final AdminAuditService audit;

    public AdminUserBusiness(AdminUserService users, AdminUserConverter converter, AdminUserMutationGuard guard, AdminAuditService audit) {
        this.converter = converter;
        this.users = users;
        this.guard = guard;
        this.audit = audit;
    }

    public PageResponse<AdminUserResponse> search(String query, UserStatus status, UserRole role, int page, int size) {
        String normalized = query == null ? null : query.trim();
        if (normalized != null && normalized.length() > 200) {
            throw new AdminException(HttpStatus.BAD_REQUEST, "검색어는 200자 이하여야 합니다.");
        }
        return converter.toPage(users.search(normalized, status, role, AdminPageRequest.page(page, size)));
    }

    public AdminUserResponse detail(long userId) {
        positiveId(userId);
        return converter.toResponse(users.getById(userId));
    }

    public PageResponse<AdminUserWorkspaceResponse> workspaces(long userId, int page, int size) {
        var pagination = AdminPageRequest.page(page, size);
        detail(userId);
        return converter.toWorkspacePage(users.workspaces(userId, pagination));
    }

    @Transactional
    public AdminUserMutationResponse changeStatus(long actorId, long userId, UserStatus status, String reason) {
        String normalizedReason = reason(reason);
        if (status != UserStatus.REGISTERED && status != UserStatus.SUSPENDED) {
            throw new AdminException(HttpStatus.BAD_REQUEST, "회원 상태는 REGISTERED 또는 SUSPENDED만 지정할 수 있습니다.");
        }
        LockedUser target = guard.lock(actorId, userId).target();
        mutable(target);
        if (actorId == userId && status == UserStatus.SUSPENDED) {
            throw new AdminException(HttpStatus.CONFLICT, "자신의 관리자 계정은 정지할 수 없습니다. 다른 관리자가 처리해야 합니다.");
        }
        if (target.status() == status) return converter.toMutationResponse(target, false, 0);
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
        users.updateStatus(userId, status, nextVersion(target), now);
        int revoked = users.revokeSessions(userId, now);
        audit.record(actorId, "USER_STATUS_CHANGED", "USER", userId, normalizedReason,
                target.status().name(), status.name());
        return converter.toMutationResponse(userId, status, target.role(), true, revoked);
    }

    @Transactional
    public AdminUserMutationResponse changeRole(long actorId, long userId, UserRole role, String reason) {
        String normalizedReason = reason(reason);
        if (role == null) throw new AdminException(HttpStatus.BAD_REQUEST, "회원 권한이 필요합니다.");
        LockedUser target = guard.lock(actorId, userId).target();
        mutable(target);
        if (actorId == userId && role != UserRole.ADMIN) {
            throw new AdminException(HttpStatus.CONFLICT, "자신의 관리자 권한은 해제할 수 없습니다. 다른 관리자가 처리해야 합니다.");
        }
        if (target.role() == role) return converter.toMutationResponse(target, false, 0);
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
        users.updateRole(userId, role, nextVersion(target), now);
        int revoked = users.revokeSessions(userId, now);
        audit.record(actorId, "USER_ROLE_CHANGED", "USER", userId, normalizedReason,
                target.role().name(), role.name());
        return converter.toMutationResponse(userId, target.status(), role, true, revoked);
    }

    @Transactional
    public AdminUserMutationResponse revokeSessions(long actorId, long userId, String reason) {
        String normalizedReason = reason(reason);
        LockedUser target = guard.lock(actorId, userId).target();
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
        long version = nextVersion(target);
        users.updateAuthVersion(userId, version, now);
        int revoked = users.revokeSessions(userId, now);
        audit.record(actorId, "USER_SESSIONS_REVOKED", "USER", userId, normalizedReason,
                Long.toString(target.authVersion()), Long.toString(version));
        return converter.toMutationResponse(target, true, revoked);
    }

    private static void mutable(LockedUser user) {
        if (user.status() == UserStatus.UNREGISTERED) {
            throw new AdminException(HttpStatus.CONFLICT, "탈퇴한 회원의 상태나 권한은 변경할 수 없습니다.");
        }
    }

    private static long nextVersion(LockedUser user) {
        if (user.authVersion() == Long.MAX_VALUE) {
            throw new AdminException(HttpStatus.CONFLICT, "세션 버전이 허용 범위를 초과했습니다.");
        }
        return user.authVersion() + 1;
    }

    private static String reason(String reason) {
        if (reason != null && reason.length() > 500) {
            throw new AdminException(HttpStatus.BAD_REQUEST, "메모는 500자 이하로 입력해 주세요.");
        }
        return reason == null ? null : reason.strip();
    }

    private static void positiveId(long userId) {
        if (userId < 1) throw new AdminException(HttpStatus.BAD_REQUEST, "올바른 회원 ID가 필요합니다.");
    }
}
