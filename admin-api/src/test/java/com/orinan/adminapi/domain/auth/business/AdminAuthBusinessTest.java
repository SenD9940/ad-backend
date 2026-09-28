package com.orinan.adminapi.domain.auth.business;
import com.orinan.adminapi.domain.auth.controller.model.AdminLoginRequest;
import com.orinan.adminapi.domain.auth.converter.AdminAuthConverter;
import com.orinan.adminapi.domain.auth.model.AdminPrincipal;
import com.orinan.adminapi.domain.auth.service.AdminAuthService;
import com.orinan.adminapi.domain.token.business.AdminTokenBusiness;
import com.orinan.adminapi.domain.token.converter.AdminTokenConverter;
import com.orinan.adminapi.domain.token.helper.AdminTokenHelper;
import com.orinan.adminapi.domain.token.model.AdminTokenClaims;
import com.orinan.adminapi.domain.token.service.AdminTokenService;
import com.orinan.db.adminauth.AdminAuthRepository;
import com.orinan.db.token.AdminTokenRepository;

import com.orinan.adminapi.domain.audit.service.AdminAuditService;
import com.orinan.adminapi.common.exception.AdminException;
import com.orinan.db.user.UserEntity;
import com.orinan.db.user.UserRepository;
import com.orinan.db.user.enums.UserRole;
import com.orinan.db.user.enums.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AdminAuthBusinessTest {
    private final UserRepository users = mock(UserRepository.class);
    private final AdminAuthRepository repository = mock(AdminAuthRepository.class);
    private final AdminTokenRepository tokenRepository = mock(AdminTokenRepository.class);
    private final PasswordEncoder passwords = mock(PasswordEncoder.class);
    private final AdminAuditService audit = mock(AdminAuditService.class);
    private final AdminTokenHelper jwt = new AdminTokenHelper("admin-test-key-with-at-least-32-bytes-secret", 30);
    private final AdminTokenBusiness tokens = new AdminTokenBusiness(
            new AdminTokenService(jwt, users, tokenRepository), new AdminTokenConverter());
    private final AdminAuthBusiness service = new AdminAuthBusiness(
            new AdminAuthService(users, repository, passwords), new AdminAuthConverter(), tokens, audit);
    private UserEntity admin;

    @BeforeEach
    void setUp() {
        admin = UserEntity.builder().id(3L).email("admin@example.com").password("encoded-password")
                .status(UserStatus.REGISTERED).role(UserRole.ADMIN).authVersion(7L).build();
        when(users.findById(3L)).thenReturn(Optional.of(admin));
        when(users.findByIdForUpdate(3L)).thenReturn(Optional.of(admin));
        when(users.findByEmailIgnoreCaseAndStatus("admin@example.com", UserStatus.REGISTERED)).thenReturn(Optional.of(admin));
        when(passwords.matches("correct", "encoded-password")).thenReturn(true);
        when(repository.displayName(3L)).thenReturn("관리자");
    }

    @Test
    void loginUpdatesTimestampReturnsOnlySafeFieldsAndAudits() {
        var response = service.login(new AdminLoginRequest(" admin@example.com ", "correct"));
        assertThat(response.user().id()).isEqualTo(3L);
        assertThat(response.user().name()).isEqualTo("관리자");
        assertThat(jwt.validationTokenWithThrow(response.accessToken())).isEqualTo(new AdminTokenClaims(3L, 7L));
        assertThat(admin.getLastLoginAt()).isNotNull();
        assertThat(response.toString()).doesNotContain(response.accessToken(), "encoded-password");
        verify(audit).record(3L, "ADMIN_LOGIN", "USER", 3L, "관리자 로그인", null, null);
    }

    @Test
    void unknownAccountWrongPasswordAndCustomerShareLoginFailure() {
        assertThatThrownBy(() -> service.login(new AdminLoginRequest("unknown@example.com", "correct")))
                .isInstanceOf(AdminException.class).hasMessage("이메일 또는 비밀번호를 확인해 주세요.");
        verify(passwords).matches(eq("correct"), startsWith("$2a$"));
        assertThatThrownBy(() -> service.login(new AdminLoginRequest("admin@example.com", "incorrect")))
                .isInstanceOf(AdminException.class).hasMessage("이메일 또는 비밀번호를 확인해 주세요.");
        admin.setRole(UserRole.CUSTOMER);
        assertThatThrownBy(() -> service.login(new AdminLoginRequest("admin@example.com", "correct")))
                .isInstanceOf(AdminException.class).hasMessage("이메일 또는 비밀번호를 확인해 주세요.");
        assertThat(admin.getLastLoginAt()).isNull();
        verifyNoInteractions(audit);
    }

    @Test
    void everyAuthenticatedRequestRechecksAccountRoleStatusAndVersion() {
        String token = jwt.issueAccessToken(3L, 7L).token();
        assertThat(service.authenticate(token)).isEqualTo(new AdminPrincipal(3L, "admin@example.com"));
        admin.setStatus(UserStatus.SUSPENDED);
        assertThatThrownBy(() -> service.authenticate(token)).isInstanceOf(AdminException.class);
        admin.setStatus(UserStatus.REGISTERED);
        admin.setRole(UserRole.CUSTOMER);
        assertThatThrownBy(() -> service.authenticate(token)).isInstanceOf(AdminException.class)
                .hasMessage("관리자 권한이 필요합니다.");
        admin.setRole(UserRole.ADMIN);
        admin.setAuthVersion(8L);
        assertThatThrownBy(() -> service.authenticate(token)).isInstanceOf(AdminException.class);
        verify(users, times(5)).findById(3L);
    }

    @Test
    void logoutRevokesEverySessionAndAuditUsesVersionOnly() {
        String oldToken = jwt.issueAccessToken(3L, 7L).token();
        service.logout(3L);
        assertThat(admin.getAuthVersion()).isEqualTo(8L);
        var ordered = inOrder(users, tokenRepository, audit);
        ordered.verify(users).findByIdForUpdate(3L);
        ordered.verify(tokenRepository).revokeRefreshTokens(eq(3L), any());
        ordered.verify(audit).record(3L, "ADMIN_LOGOUT", "USER", 3L, "관리자 로그아웃", "7", "8");
        assertThatThrownBy(() -> service.authenticate(oldToken)).isInstanceOf(AdminException.class);
        assertThat(service.authenticate(jwt.issueAccessToken(3L, 8L).token())).isNotNull();
    }

    @Test
    void removedAccountAndInvalidTokenNeverAuthenticate() {
        when(users.findById(3L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.authenticate(jwt.issueAccessToken(3L, 7L).token())).isInstanceOf(AdminException.class);
        clearInvocations(users);
        assertThatThrownBy(() -> service.authenticate("broken")).isInstanceOf(AdminException.class);
        verifyNoInteractions(users);
    }
}
