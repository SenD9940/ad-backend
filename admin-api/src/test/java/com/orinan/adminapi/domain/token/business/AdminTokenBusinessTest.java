package com.orinan.adminapi.domain.token.business;

import com.orinan.adminapi.domain.token.converter.AdminTokenConverter;
import com.orinan.adminapi.domain.token.exception.AdminTokenErrorCode;
import com.orinan.adminapi.domain.token.exception.AdminTokenException;
import com.orinan.adminapi.domain.token.ifs.AdminTokenHelperIfs;
import com.orinan.adminapi.domain.token.model.AdminTokenDto;
import com.orinan.adminapi.domain.token.service.AdminTokenService;
import com.orinan.db.token.AdminTokenRepository;
import com.orinan.db.user.UserEntity;
import com.orinan.db.user.UserRepository;
import com.orinan.db.user.enums.UserRole;
import com.orinan.db.user.enums.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class AdminTokenBusinessTest {
    private final AdminTokenHelperIfs helper = mock(AdminTokenHelperIfs.class);
    private final UserRepository users = mock(UserRepository.class);
    private final AdminTokenRepository tokens = mock(AdminTokenRepository.class);
    private final AdminTokenBusiness business = new AdminTokenBusiness(
            new AdminTokenService(helper, users, tokens), new AdminTokenConverter());
    private UserEntity admin;

    @BeforeEach
    void setUp() {
        admin = UserEntity.builder().id(3L).email("admin@example.com")
                .status(UserStatus.REGISTERED).role(UserRole.ADMIN).authVersion(7L).build();
    }

    @Test
    void issuanceLocksTheAccountBeforeUsingItsCurrentVersionAndConvertsTheTokenResponse() {
        when(users.findByIdForUpdate(3L)).thenAnswer(invocation -> {
            // Simulate an already-completed revocation observed after acquiring the lock.
            admin.setAuthVersion(8L);
            return Optional.of(admin);
        });
        when(users.findById(3L)).thenReturn(Optional.of(admin));
        Instant expiresAt = Instant.parse("2026-09-28T01:30:00Z");
        when(helper.issueAccessToken(3L, 8L)).thenReturn(new AdminTokenDto("issued-token", expiresAt, 1800L));

        var response = business.issueToken(3L);

        var ordered = inOrder(users, helper);
        ordered.verify(users).findByIdForUpdate(3L);
        ordered.verify(users).findById(3L);
        ordered.verify(helper).issueAccessToken(3L, 8L);
        assertThat(response.accessToken()).isEqualTo("issued-token");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresAt()).isEqualTo(expiresAt);
        assertThat(response.expiresIn()).isEqualTo(1800L);
        assertThat(response.toString()).doesNotContain("issued-token");
        verifyNoInteractions(tokens);
    }

    @Test
    void failedAccountLockCannotIssueOrExpireAnyToken() {
        admin.setRole(UserRole.CUSTOMER);
        when(users.findByIdForUpdate(3L)).thenReturn(Optional.of(admin));

        assertThatThrownBy(() -> business.issueToken(3L))
                .isInstanceOfSatisfying(AdminTokenException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(AdminTokenErrorCode.ADMIN_PERMISSION_DENIED));
        assertThatThrownBy(() -> business.expireToken(3L))
                .isInstanceOfSatisfying(AdminTokenException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(AdminTokenErrorCode.ADMIN_PERMISSION_DENIED));

        assertThat(admin.getAuthVersion()).isEqualTo(7L);
        verify(users, never()).findById(anyLong());
        verifyNoInteractions(helper, tokens);
    }

    @Test
    void expiryLocksTheUserBeforeBulkRevocationAndAdvancesVersionEvenWithoutRefreshSessions() {
        when(users.findByIdForUpdate(3L)).thenReturn(Optional.of(admin));
        LocalDateTime before = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
        when(tokens.revokeRefreshTokens(eq(3L), any())).thenAnswer(invocation -> {
            assertThat(admin.getAuthVersion()).isEqualTo(8L);
            LocalDateTime revokedAt = invocation.getArgument(1);
            assertThat(revokedAt).isBetween(before, LocalDateTime.now(ZoneId.of("Asia/Seoul")));
            return 0;
        });

        var result = business.expireToken(3L);

        var ordered = inOrder(users, tokens);
        ordered.verify(users).findByIdForUpdate(3L);
        ordered.verify(tokens).revokeRefreshTokens(eq(3L), any());
        assertThat(result.previousAuthVersion()).isEqualTo(7L);
        assertThat(result.authVersion()).isEqualTo(8L);
        assertThat(result.revokedSessions()).isZero();
        verifyNoInteractions(helper);
    }
}
