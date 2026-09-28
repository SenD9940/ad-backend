package com.orinan.adminapi.domain.user.service;

import com.orinan.adminapi.common.exception.AdminException;
import com.orinan.db.user.projection.AdminUserLockProjection;
import com.orinan.db.user.enums.UserRole;
import com.orinan.db.user.enums.UserStatus;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** All user mutations lock actor and target in the same order before checking current authority. */
@Component
public class AdminUserMutationGuard {
    private final AdminUserService users;

    public AdminUserMutationGuard(AdminUserService users) {
        this.users = users;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public LockedUsers lock(long actorId, long targetId) {
        if (actorId < 1 || targetId < 1) {
            throw new AdminException(HttpStatus.BAD_REQUEST, "올바른 회원 ID가 필요합니다.");
        }
        long firstId = Math.min(actorId, targetId);
        long secondId = Math.max(actorId, targetId);
        LockedUser first = users.lockById(firstId).map(AdminUserMutationGuard::toLockedUser)
                .orElseThrow(() -> missing(firstId, actorId));
        LockedUser second = firstId == secondId ? first
                : users.lockById(secondId).map(AdminUserMutationGuard::toLockedUser)
                .orElseThrow(() -> missing(secondId, actorId));
        LockedUser actor = first.id() == actorId ? first : second;
        LockedUser target = first.id() == targetId ? first : second;
        if (actor.status() != UserStatus.REGISTERED || actor.role() != UserRole.ADMIN) {
            throw new AdminException(HttpStatus.FORBIDDEN, "현재 관리자 권한이 필요합니다.");
        }
        return new LockedUsers(actor, target);
    }

    private static LockedUser toLockedUser(AdminUserLockProjection user) {
        return new LockedUser(user.id(), user.email(), user.status(), user.role(), user.authVersion());
    }

    private static AdminException missing(long id, long actorId) {
        return id == actorId
                ? new AdminException(HttpStatus.FORBIDDEN, "현재 관리자 권한이 필요합니다.")
                : new AdminException(HttpStatus.NOT_FOUND, "회원을 찾을 수 없습니다.");
    }

    public record LockedUser(long id, String email, UserStatus status, UserRole role, long authVersion) {}
    public record LockedUsers(LockedUser actor, LockedUser target) {}
}
