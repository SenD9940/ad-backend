package com.orinan.adminapi.domain.token.service;

import com.orinan.adminapi.domain.token.exception.AdminTokenErrorCode;
import com.orinan.adminapi.domain.token.exception.AdminTokenException;
import com.orinan.adminapi.domain.token.ifs.AdminTokenHelperIfs;
import com.orinan.adminapi.domain.token.model.AdminTokenClaims;
import com.orinan.adminapi.domain.token.model.AdminTokenDto;
import com.orinan.db.token.AdminTokenRepository;
import com.orinan.db.user.UserEntity;
import com.orinan.db.user.UserRepository;
import com.orinan.db.user.enums.UserRole;
import com.orinan.db.user.enums.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class AdminTokenServiceTest {
    private final AdminTokenHelperIfs helper = mock(AdminTokenHelperIfs.class);
    private final UserRepository users = mock(UserRepository.class);
    private final AdminTokenRepository tokens = mock(AdminTokenRepository.class);
    private final AdminTokenService service = new AdminTokenService(helper, users, tokens);
    private UserEntity admin;

    @BeforeEach
    void setUp() {
        admin = UserEntity.builder().id(3L).email("admin@example.com")
                .status(UserStatus.REGISTERED).role(UserRole.ADMIN).authVersion(7L).build();
    }

    @Test
    void accessValidationRechecksCurrentStatusRoleAndVersionOnEveryRequest() {
        when(helper.validationTokenWithThrow("signed-token")).thenReturn(new AdminTokenClaims(3L, 7L));
        when(users.findById(3L)).thenReturn(Optional.of(admin));

        assertThat(service.validateAccessToken("signed-token")).isEqualTo(3L);
        admin.setStatus(UserStatus.SUSPENDED);
        assertTokenError(() -> service.validateAccessToken("signed-token"), AdminTokenErrorCode.INVALID_TOKEN);
        admin.setStatus(UserStatus.UNREGISTERED);
        assertTokenError(() -> service.validateAccessToken("signed-token"), AdminTokenErrorCode.INVALID_TOKEN);
        admin.setStatus(UserStatus.REGISTERED);
        admin.setRole(UserRole.CUSTOMER);
        assertTokenError(() -> service.validateAccessToken("signed-token"), AdminTokenErrorCode.ADMIN_PERMISSION_DENIED);
        admin.setRole(UserRole.ADMIN);
        admin.setAuthVersion(8L);
        assertTokenError(() -> service.validateAccessToken("signed-token"), AdminTokenErrorCode.INVALID_TOKEN);

        verify(users, times(5)).findById(3L);
        verifyNoInteractions(tokens);
    }

    @Test
    void invalidCryptographyStopsBeforeAnyDatabaseLookup() {
        var failure = new AdminTokenException(AdminTokenErrorCode.INVALID_TOKEN);
        when(helper.validationTokenWithThrow("tampered-token")).thenThrow(failure);

        assertThatThrownBy(() -> service.validateAccessToken("tampered-token")).isSameAs(failure);

        verifyNoInteractions(users, tokens);
    }

    @Test
    void missingAccountsCannotIssueValidateOrLockTokens() {
        when(users.findById(3L)).thenReturn(Optional.empty());
        when(users.findByIdForUpdate(3L)).thenReturn(Optional.empty());
        when(helper.validationTokenWithThrow("removed-account-token")).thenReturn(new AdminTokenClaims(3L, 7L));

        assertTokenError(() -> service.issueAccessToken(3L), AdminTokenErrorCode.INVALID_TOKEN);
        assertTokenError(() -> service.validateAccessToken("removed-account-token"), AdminTokenErrorCode.INVALID_TOKEN);
        assertTokenError(() -> service.lockActiveAdmin(3L), AdminTokenErrorCode.INVALID_TOKEN);

        verify(helper, never()).issueAccessToken(anyLong(), anyLong());
        verifyNoInteractions(tokens);
    }

    @Test
    void nonpositiveAccountIdsAreRejectedBeforeDatabaseAccess() {
        for (long id : new long[]{0L, -1L}) {
            when(helper.validationTokenWithThrow("invalid-account-token")).thenReturn(new AdminTokenClaims(id, 7L));

            assertTokenError(() -> service.issueAccessToken(id), AdminTokenErrorCode.INVALID_TOKEN);
            assertTokenError(() -> service.lockActiveAdmin(id), AdminTokenErrorCode.INVALID_TOKEN);
            assertTokenError(() -> service.validateAccessToken("invalid-account-token"), AdminTokenErrorCode.INVALID_TOKEN);
        }

        verifyNoInteractions(users, tokens);
        verify(helper, never()).issueAccessToken(anyLong(), anyLong());
    }

    @Test
    void issuanceUsesTheCurrentDatabaseVersionAndRejectsIneligibleAccounts() {
        admin.setAuthVersion(19L);
        when(users.findById(3L)).thenReturn(Optional.of(admin));
        var issued = new AdminTokenDto("new-token", Instant.parse("2026-09-28T01:30:00Z"), 1800L);
        when(helper.issueAccessToken(3L, 19L)).thenReturn(issued);

        assertThat(service.issueAccessToken(3L)).isSameAs(issued);
        admin.setRole(UserRole.CUSTOMER);
        assertTokenError(() -> service.issueAccessToken(3L), AdminTokenErrorCode.ADMIN_PERMISSION_DENIED);
        admin.setRole(UserRole.ADMIN);
        admin.setStatus(UserStatus.SUSPENDED);
        assertTokenError(() -> service.issueAccessToken(3L), AdminTokenErrorCode.INVALID_TOKEN);

        verify(helper).issueAccessToken(3L, 19L);
        verifyNoMoreInteractions(helper);
        verifyNoInteractions(tokens);
    }

    @Test
    void versionExhaustionCannotMutateTheUserOrRefreshTokens() {
        admin.setAuthVersion(Long.MAX_VALUE);

        assertTokenError(() -> service.revokeSessions(admin), AdminTokenErrorCode.SESSION_VERSION_EXHAUSTED);

        assertThat(admin.getAuthVersion()).isEqualTo(Long.MAX_VALUE);
        verifyNoInteractions(helper, users, tokens);
    }

    @Test
    void revocationRejectsInactiveOrDemotedAccountsWithoutChangingTheirVersion() {
        admin.setStatus(UserStatus.SUSPENDED);
        assertTokenError(() -> service.revokeSessions(admin), AdminTokenErrorCode.INVALID_TOKEN);
        admin.setStatus(UserStatus.REGISTERED);
        admin.setRole(UserRole.CUSTOMER);
        assertTokenError(() -> service.revokeSessions(admin), AdminTokenErrorCode.ADMIN_PERMISSION_DENIED);

        assertThat(admin.getAuthVersion()).isEqualTo(7L);
        verifyNoInteractions(tokens);
    }

    private void assertTokenError(Runnable action, AdminTokenErrorCode expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(AdminTokenException.class,
                exception -> assertThat(exception.errorCode()).isEqualTo(expected));
    }
}
