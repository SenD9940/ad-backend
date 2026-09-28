package com.orinan.api.domain.token;

import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.token.converter.TokenConverter;
import com.orinan.api.domain.token.exception.TokenErrorCode;
import com.orinan.api.domain.token.ifs.TokenHelperIfs;
import com.orinan.api.domain.token.model.TokenDto;
import com.orinan.api.domain.token.service.TokenService;
import com.orinan.db.crypto.SearchHashEncoder;
import com.orinan.db.token.TokenEntity;
import com.orinan.db.token.TokenRepository;
import com.orinan.db.token.enums.TokenStatus;
import com.orinan.db.user.UserEntity;
import com.orinan.db.user.UserRepository;
import com.orinan.db.user.enums.UserStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.*;

class TokenServiceTest {
    private final TokenHelperIfs helper = mock(TokenHelperIfs.class);
    private final TokenRepository tokens = mock(TokenRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final SearchHashEncoder hashes = mock(SearchHashEncoder.class);
    private final TokenService service = new TokenService(helper, tokens, new TokenConverter(), hashes, users);

    @Test
    void newAccessTokenContainsTheCurrentRegisteredUsersSessionVersion() {
        when(users.findByIdAndStatus(7L, UserStatus.REGISTERED)).thenReturn(Optional.of(user(9)));
        var result = TokenDto.builder().token("signed-access-token").build();
        when(helper.issueAccessToken(anyMap())).thenReturn(result);

        assertThat(service.issueAccessToken(7L)).isSameAs(result);

        verify(helper).issueAccessToken(Map.of("userId", 7L, "authVersion", 9L));
    }

    @Test
    void missingOrSuspendedUsersCannotReceiveAccessTokens() {
        assertInvalid(() -> service.issueAccessToken(7L));
        verifyNoInteractions(helper);
    }

    @Test
    void legacyAccessTokensWorkOnlyBeforeFirstAdministrativeRevocation() {
        when(helper.validationTokenWithThrow("legacy-token")).thenReturn(Map.of("userId", 7));
        var user = user(0);
        when(users.findByIdAndStatus(7L, UserStatus.REGISTERED)).thenReturn(Optional.of(user));

        assertThat(service.validateAccessToken("legacy-token")).isEqualTo(7L);
        user.setAuthVersion(1);
        assertInvalid(() -> service.validateAccessToken("legacy-token"));
    }

    @Test
    void revokedAccessTokenCannotBecomeValidAfterTheAccountIsReactivated() {
        when(helper.validationTokenWithThrow("old-token")).thenReturn(Map.of("userId", 7, "authVersion", 3));
        when(users.findByIdAndStatus(7L, UserStatus.REGISTERED)).thenReturn(Optional.of(user(4)));

        assertInvalid(() -> service.validateAccessToken("old-token"));
    }

    @Test
    void deletedOrNonRegisteredUsersCannotUseOtherwiseValidAccessTokens() {
        when(helper.validationTokenWithThrow("access-token")).thenReturn(Map.of("userId", 7, "authVersion", 0));
        assertInvalid(() -> service.validateAccessToken("access-token"));
    }

    @ParameterizedTest
    @MethodSource("validVersions")
    void acceptsOnlySupportedIntegralVersionRepresentations(Object version) {
        when(helper.validationTokenWithThrow("access-token")).thenReturn(Map.of("userId", 7, "authVersion", version));
        when(users.findByIdAndStatus(7L, UserStatus.REGISTERED)).thenReturn(Optional.of(user(3)));
        assertThat(service.validateAccessToken("access-token")).isEqualTo(7L);
    }

    static Stream<Object> validVersions() {
        return Stream.of((byte) 3, (short) 3, 3, 3L, BigInteger.valueOf(3));
    }

    @ParameterizedTest
    @MethodSource("invalidVersions")
    void rejectsMalformedVersionClaimsWithoutDatabaseOrTokenDetails(Object version) {
        var claims = new HashMap<String, Object>();
        claims.put("userId", 7);
        claims.put("authVersion", version);
        when(helper.validationTokenWithThrow("private-access-token")).thenReturn(claims);

        assertInvalid(() -> service.validateAccessToken("private-access-token"));
        verifyNoInteractions(users);
    }

    static Stream<Object> invalidVersions() {
        return Stream.of(null, -1, -1L, "3", 3.0, 3.5, true, new BigDecimal("3"),
                BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE), Map.of("authVersion", 3));
    }

    @Test
    void administratorAndRefreshTokensWithNoUserClaimAreNotServiceAccessTokens() {
        when(helper.validationTokenWithThrow("admin-token")).thenReturn(Map.of("adminId", 7, "authVersion", 0));
        when(helper.validationTokenWithThrow("refresh-token")).thenReturn(Map.of());

        assertInvalid(() -> service.validateAccessToken("admin-token"));
        assertInvalid(() -> service.validateAccessToken("refresh-token"));
        verifyNoInteractions(users);
    }

    @Test
    void nullClaimsAreRejectedAsInvalidCredentials() {
        when(helper.validationTokenWithThrow("malformed-token")).thenReturn(null);
        assertInvalid(() -> service.validateAccessToken("malformed-token"));
        assertInvalid(() -> service.validateRefreshToken("malformed-token"));
        verifyNoInteractions(users, tokens);
    }

    @Test
    void refreshLocksTheUserBeforeRevalidatingAndLockingTheActiveToken() {
        var stored = prepareRefresh();
        when(tokens.findByRefreshTokenHashAndStatus("token-hash", TokenStatus.ACTIVE)).thenReturn(Optional.of(stored));

        assertThat(service.validateRefreshToken("refresh-token")).isSameAs(stored);

        var order = inOrder(helper, hashes, users, tokens);
        order.verify(helper).validationTokenWithThrow("refresh-token");
        order.verify(hashes).encode("refresh-token");
        order.verify(tokens).findByRefreshTokenHash("token-hash");
        order.verify(users).findByIdForUpdate(7L);
        order.verify(tokens).findByRefreshTokenHashAndStatus("token-hash", TokenStatus.ACTIVE);
    }

    @Test
    void refreshCannotResurrectATokenRevokedWhileWaitingForTheUserLock() {
        prepareRefresh();
        when(tokens.findByRefreshTokenHashAndStatus("token-hash", TokenStatus.ACTIVE)).thenReturn(Optional.empty());

        assertInvalid(() -> service.validateRefreshToken("refresh-token"));

        verify(helper, never()).issueAccessToken(anyMap());
        verify(helper, never()).issueRefreshToken();
        verify(tokens, never()).save(any());
    }

    @Test
    void refreshIsRejectedBeforeTokenLockWhenTheUserIsSuspended() {
        prepareRefresh();
        var suspended = user(1);
        suspended.setStatus(UserStatus.SUSPENDED);
        when(users.findByIdForUpdate(7L)).thenReturn(Optional.of(suspended));

        assertInvalid(() -> service.validateRefreshToken("refresh-token"));

        verify(tokens, never()).findByRefreshTokenHashAndStatus(anyString(), any());
    }

    @Test
    void refreshCannotSwitchOwnersBetweenTheLookupAndLockedRead() {
        prepareRefresh();
        when(tokens.findByRefreshTokenHashAndStatus("token-hash", TokenStatus.ACTIVE)).thenReturn(
                Optional.of(TokenEntity.builder().id(1L).userId(99L).status(TokenStatus.ACTIVE).build()));
        assertInvalid(() -> service.validateRefreshToken("refresh-token"));
    }

    private TokenEntity prepareRefresh() {
        var stored = TokenEntity.builder().id(1L).userId(7L).status(TokenStatus.ACTIVE).refreshTokenHash("token-hash").build();
        when(helper.validationTokenWithThrow("refresh-token")).thenReturn(Map.of());
        when(hashes.encode("refresh-token")).thenReturn("token-hash");
        when(tokens.findByRefreshTokenHash("token-hash")).thenReturn(Optional.of(stored));
        when(users.findByIdForUpdate(7L)).thenReturn(Optional.of(user(1)));
        return stored;
    }

    private UserEntity user(long authVersion) {
        return UserEntity.builder().id(7L).status(UserStatus.REGISTERED).authVersion(authVersion).build();
    }

    private void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ApiException.class, exception -> {
            assertThat(exception.getCodeIfs()).isEqualTo(TokenErrorCode.INVALID_TOKEN);
            assertThat(exception.getCause()).isNull();
            assertThat(exception.getMessage()).doesNotContain("private-access-token", "refresh-token");
        });
    }
}
