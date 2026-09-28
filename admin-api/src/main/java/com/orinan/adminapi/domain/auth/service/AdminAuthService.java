package com.orinan.adminapi.domain.auth.service;

import com.orinan.adminapi.common.exception.AdminException;
import com.orinan.db.adminauth.AdminAuthRepository;
import com.orinan.db.user.UserEntity;
import com.orinan.db.user.UserRepository;
import com.orinan.db.user.enums.UserRole;
import com.orinan.db.user.enums.UserStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;

@Service
@RequiredArgsConstructor
public class AdminAuthService {
    // BCrypt work is also performed when the account does not exist.
    private static final String DUMMY_PASSWORD = "$2a$10$7EqJtq98hPqEX7fNZaFWoO5PtfSNePI0NOHHMxhlDvl/ZrqFSAAHq";
    private final UserRepository users;
    private final AdminAuthRepository repository;
    private final PasswordEncoder passwords;

    public UserEntity authenticateCredentials(String email, String password) {
        UserEntity user = users.findByEmailIgnoreCaseAndStatus(email.strip(), UserStatus.REGISTERED).orElse(null);
        boolean matches = passwords.matches(password, user == null ? DUMMY_PASSWORD : user.getPassword());
        if (user == null || !matches || user.getRole() != UserRole.ADMIN) {
            throw new AdminException(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호를 확인해 주세요.");
        }
        return user;
    }

    public UserEntity findActiveAdmin(long userId) {
        var user = users.findById(userId).orElseThrow(AdminAuthService::unauthorized);
        requireAdmin(user);
        return user;
    }

    public void updateLastLogin(UserEntity user) {
        user.setLastLoginAt(LocalDateTime.now(ZoneId.of("Asia/Seoul")));
    }

    public String displayName(long userId) {
        return repository.displayName(userId);
    }

    private static void requireAdmin(UserEntity user) {
        if (user.getStatus() != UserStatus.REGISTERED) throw unauthorized();
        if (user.getRole() != UserRole.ADMIN) {
            throw new AdminException(HttpStatus.FORBIDDEN, "관리자 권한이 필요합니다.");
        }
    }

    private static AdminException unauthorized() {
        return new AdminException(HttpStatus.UNAUTHORIZED, "관리자 로그인이 필요합니다.");
    }
}
