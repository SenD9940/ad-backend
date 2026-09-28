package com.orinan.adminapi.domain.token.service;

import com.orinan.adminapi.domain.token.exception.AdminTokenErrorCode;
import com.orinan.adminapi.domain.token.exception.AdminTokenException;
import com.orinan.adminapi.domain.token.ifs.AdminTokenHelperIfs;
import com.orinan.adminapi.domain.token.model.AdminTokenDto;
import com.orinan.adminapi.domain.token.model.AdminTokenRevocation;
import com.orinan.db.token.AdminTokenRepository;
import com.orinan.db.user.UserEntity;
import com.orinan.db.user.UserRepository;
import com.orinan.db.user.enums.UserRole;
import com.orinan.db.user.enums.UserStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;

@Service
@RequiredArgsConstructor
public class AdminTokenService {
    private final AdminTokenHelperIfs tokenHelper;
    private final UserRepository users;
    private final AdminTokenRepository tokens;

    public AdminTokenDto issueAccessToken(long userId) {
        var user = findActiveAdmin(userId);
        return tokenHelper.issueAccessToken(user.getId(), user.getAuthVersion());
    }

    public long validateAccessToken(String token) {
        var claims = tokenHelper.validationTokenWithThrow(token);
        var user = findActiveAdmin(claims.userId());
        if (user.getAuthVersion() != claims.authVersion()) throw invalidToken();
        return user.getId();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UserEntity lockActiveAdmin(long userId) {
        requirePositiveId(userId);
        var user = users.findByIdForUpdate(userId).orElseThrow(AdminTokenService::invalidToken);
        requireAdmin(user);
        return user;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public AdminTokenRevocation revokeSessions(UserEntity user) {
        requireAdmin(user);
        long previousVersion = user.getAuthVersion();
        if (previousVersion == Long.MAX_VALUE) {
            throw new AdminTokenException(AdminTokenErrorCode.SESSION_VERSION_EXHAUSTED);
        }
        long version = Math.addExact(previousVersion, 1);
        user.setAuthVersion(version);
        int revoked = tokens.revokeRefreshTokens(user.getId(), LocalDateTime.now(ZoneId.of("Asia/Seoul")));
        return new AdminTokenRevocation(previousVersion, version, revoked);
    }

    private UserEntity findActiveAdmin(long userId) {
        requirePositiveId(userId);
        var user = users.findById(userId).orElseThrow(AdminTokenService::invalidToken);
        requireAdmin(user);
        return user;
    }

    private static void requireAdmin(UserEntity user) {
        if (user.getStatus() != UserStatus.REGISTERED) throw invalidToken();
        if (user.getRole() != UserRole.ADMIN) {
            throw new AdminTokenException(AdminTokenErrorCode.ADMIN_PERMISSION_DENIED);
        }
    }

    private static void requirePositiveId(long userId) {
        if (userId < 1) throw invalidToken();
    }

    private static AdminTokenException invalidToken() {
        return new AdminTokenException(AdminTokenErrorCode.INVALID_TOKEN);
    }
}
