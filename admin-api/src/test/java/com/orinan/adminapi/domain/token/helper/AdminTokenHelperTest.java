package com.orinan.adminapi.domain.token.helper;

import com.orinan.adminapi.domain.token.exception.AdminTokenErrorCode;
import com.orinan.adminapi.domain.token.exception.AdminTokenException;
import com.orinan.adminapi.domain.token.model.AdminTokenClaims;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminTokenHelperTest {
    static final String SECRET = "admin-test-key-with-at-least-32-bytes-secret";
    static final Instant NOW = Instant.parse("2026-09-28T01:00:00Z");
    private final AdminTokenHelper helper = new AdminTokenHelper(SECRET, 30, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void issuedTokenUsesSeparateSubjectAndAudienceWithoutServiceUserId() {
        var result = helper.issueAccessToken(3L, 7L);
        assertThat(result.expiredAt()).isEqualTo(NOW.plusSeconds(1800));
        assertThat(result.expiresIn()).isEqualTo(1800);
        assertThat(helper.validationTokenWithThrow(result.token())).isEqualTo(new AdminTokenClaims(3L, 7L));
        var claims = Jwts.parser().verifyWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .clock(() -> Date.from(NOW)).build().parseSignedClaims(result.token()).getPayload();
        assertThat(claims).doesNotContainKey("userId");
        assertThat(result.toString()).doesNotContain(result.token());
    }

    @Test
    void normalServiceTokensCannotAuthenticateAsAdmin() {
        String token = Jwts.builder().claim("userId", 3L).expiration(Date.from(NOW.plusSeconds(1200)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
        reject(token);
    }

    @Test
    void refusesExpiredMissingExpirationWrongIssuerAudienceAndUse() {
        reject(signed(jwt -> jwt.expiration(Date.from(NOW.minusSeconds(1)))));
        reject(signed(jwt -> jwt.claim("exp", null)));
        reject(signed(jwt -> jwt.issuer("service-api")));
        reject(signed(jwt -> jwt.audience().add("service-api")));
        reject(signed(jwt -> jwt.claim("token_use", "REFRESH")));
        reject(signed(jwt -> jwt.claim("userId", 3L)));
        reject(signed(jwt -> jwt.issuedAt(Date.from(NOW.plusSeconds(60)))));
    }

    @Test
    void refusesInvalidIdsAndNonIntegralOrMissingVersion() {
        for (String subject : new String[]{"0", "-1", "03", "user", "9223372036854775808"}) {
            reject(signed(jwt -> jwt.subject(subject)));
        }
        for (Object version : new Object[]{-1L, 1.5, "1", Map.of("x", 1)}) {
            reject(signed(jwt -> jwt.claim("auth_version", version)));
        }
        reject(signed(jwt -> jwt.claim("auth_version", null)));
        reject(signed(jwt -> jwt.claim("iat", null)));
    }

    @Test
    void refusesTamperedMalformedAndExcessivelyLongTokens() {
        reject("not-a-token");
        reject("x".repeat(8193));
        reject(signed(jwt -> { }) + "broken");
        reject(null);
    }

    @Test
    void classifiesExpirationWithoutExposingJwtClaimsInError() {
        String token = signed(jwt -> jwt.expiration(Date.from(NOW.minusSeconds(1))));

        assertThatThrownBy(() -> helper.validationTokenWithThrow(token))
                .isInstanceOfSatisfying(AdminTokenException.class, error -> {
                    assertThat(error.errorCode()).isEqualTo(AdminTokenErrorCode.EXPIRED_TOKEN);
                    assertThat(error.getCause()).isNull();
                    assertThat(error.getMessage()).doesNotContain(token).isEqualTo("관리자 로그인이 필요합니다.");
                });
    }

    @Test
    void rejectsInvalidIssuanceIdentityAndUnsafeLifetime() {
        assertThatThrownBy(() -> helper.issueAccessToken(0, 0)).isInstanceOf(AdminTokenException.class);
        assertThatThrownBy(() -> helper.issueAccessToken(3, -1)).isInstanceOf(AdminTokenException.class);
        assertThatThrownBy(() -> new AdminTokenHelper(SECRET, 0, Clock.fixed(NOW, ZoneOffset.UTC)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AdminTokenHelper(SECRET, 61, Clock.fixed(NOW, ZoneOffset.UTC)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private String signed(Consumer<JwtBuilder> customization) {
        var builder = Jwts.builder().issuer(AdminTokenHelper.ISSUER).audience().add(AdminTokenHelper.ISSUER).and()
                .subject("3").claim("token_use", "ADMIN_ACCESS").claim("auth_version", 7)
                .issuedAt(Date.from(NOW)).expiration(Date.from(NOW.plusSeconds(1800)));
        customization.accept(builder);
        return builder.signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256).compact();
    }

    private void reject(String token) {
        assertThatThrownBy(() -> helper.validationTokenWithThrow(token)).isInstanceOf(AdminTokenException.class)
                .hasMessage("관리자 로그인이 필요합니다.");
    }
}
