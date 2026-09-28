package com.orinan.adminapi.domain.user.service;

import com.orinan.adminapi.common.exception.AdminException;
import com.orinan.db.user.AdminUserRepository;
import com.orinan.db.user.enums.UserRole;
import com.orinan.db.user.enums.UserStatus;
import com.orinan.db.user.projection.AdminUserLockProjection;
import com.orinan.db.user.projection.AdminUserSummaryProjection;
import com.orinan.db.user.projection.AdminUserWorkspaceProjection;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;

/** Persistence operations used within the business layer's transaction boundary. */
@Service
@RequiredArgsConstructor
public class AdminUserService {
    private final AdminUserRepository users;

    public Page<AdminUserSummaryProjection> search(String query, UserStatus status, UserRole role, Pageable page) {
        return users.search(query, status, role, page);
    }

    public AdminUserSummaryProjection getById(long userId) {
        return users.findById(userId).orElseThrow(() ->
                new AdminException(HttpStatus.NOT_FOUND, "회원을 찾을 수 없습니다."));
    }

    public Page<AdminUserWorkspaceProjection> workspaces(long userId, Pageable page) {
        return users.workspaces(userId, page);
    }

    public Optional<AdminUserLockProjection> lockById(long userId) {
        return users.lockById(userId);
    }

    public void updateStatus(long userId, UserStatus status, long version, LocalDateTime now) {
        users.updateStatus(userId, status, version, now);
    }

    public void updateRole(long userId, UserRole role, long version, LocalDateTime now) {
        users.updateRole(userId, role, version, now);
    }

    public void updateAuthVersion(long userId, long version, LocalDateTime now) {
        users.updateAuthVersion(userId, version, now);
    }

    public int revokeSessions(long userId, LocalDateTime now) {
        return users.revokeSessions(userId, now);
    }
}
