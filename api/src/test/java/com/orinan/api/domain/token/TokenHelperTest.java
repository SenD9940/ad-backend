package com.orinan.api.domain.token;

import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.token.exception.TokenErrorCode;
import com.orinan.api.domain.token.helper.TokenHelper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenHelperTest {
    private static final String TEST_KEY = "test-only-0123456789abcdef0123456789abcdef0123456789";
    private final TokenHelper helper = new TokenHelper();

    @BeforeEach
    void configure() {
        ReflectionTestUtils.setField(helper, "secretKey", TEST_KEY);
        ReflectionTestUtils.setField(helper, "accessTokenPlusHour", 1L);
        ReflectionTestUtils.setField(helper, "refreshTokenPlusHour", 24L);
    }

    @Test
    void signedAccessTokenRoundTripKeepsTheAdministrativeRevocationVersion() {
        var issued = helper.issueAccessToken(Map.of("userId", 7L, "authVersion", 12L));
        var claims = helper.validationTokenWithThrow(issued.getToken());

        assertThat(claims.get("userId")).isEqualTo(7);
        assertThat(claims.get("authVersion")).isEqualTo(12);
        assertThat(claims).containsKeys("jti", "iat", "exp");
        assertThat(helper.validationTokenWithThrow(helper.issueRefreshToken().getToken()))
                .doesNotContainKeys("userId", "authVersion");
    }

    @Test
    void expiredJwtDoesNotLeakRawTokenOrClaimsIntoExceptionCauses() {
        var token = Jwts.builder().claim("privateDetail", "private-claim-value")
                .expiration(new Date(System.currentTimeMillis() - 60_000))
                .signWith(Keys.hmacShaKeyFor(TEST_KEY.getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertThatThrownBy(() -> helper.validationTokenWithThrow(token))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getCodeIfs()).isEqualTo(TokenErrorCode.EXPIRED_TOKEN);
                    assertThat(exception.getCause()).isNull();
                    assertThat(exception.toString()).doesNotContain(token, "private-claim-value");
                });
    }

    @Test
    void malformedJwtKeepsThePublicErrorWithoutParserDetails() {
        assertThatThrownBy(() -> helper.validationTokenWithThrow("malformed-private-token"))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getCodeIfs()).isEqualTo(TokenErrorCode.TOKEN_EXCEPTION);
                    assertThat(exception.getCause()).isNull();
                    assertThat(exception.toString()).doesNotContain("malformed-private-token");
                });
    }
}
