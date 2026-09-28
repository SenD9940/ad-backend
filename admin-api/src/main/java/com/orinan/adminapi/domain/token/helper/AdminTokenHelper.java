package com.orinan.adminapi.domain.token.helper;

import com.orinan.adminapi.domain.token.exception.AdminTokenErrorCode;
import com.orinan.adminapi.domain.token.exception.AdminTokenException;
import com.orinan.adminapi.domain.token.ifs.AdminTokenHelperIfs;
import com.orinan.adminapi.domain.token.model.AdminTokenClaims;
import com.orinan.adminapi.domain.token.model.AdminTokenDto;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Set;
import java.util.UUID;

@Component
public class AdminTokenHelper implements AdminTokenHelperIfs {
    static final String ISSUER = "ad-admin-api";
    private final SecretKey key;
    private final Duration lifetime;
    private final Clock clock;

    @Autowired
    public AdminTokenHelper(@Value("${token.secret.key}") String secret,
                           @Value("${app.admin.access-token-minutes:30}") long minutes) {
        this(secret, minutes, Clock.systemUTC());
    }

    AdminTokenHelper(String secret, long minutes, Clock clock) {
        if (minutes < 1 || minutes > 60) {
            throw new IllegalArgumentException("Admin access token lifetime must be between 1 and 60 minutes");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.lifetime = Duration.ofMinutes(minutes);
        this.clock = clock;
    }

    @Override
    public AdminTokenDto issueAccessToken(long userId, long authVersion) {
        if (userId <= 0 || authVersion < 0) {
            throw new AdminTokenException(AdminTokenErrorCode.INVALID_TOKEN);
        }
        Instant now = clock.instant();
        Instant expiresAt = now.plus(lifetime);
        try {
            String token = Jwts.builder()
                    .issuer(ISSUER).audience().add(ISSUER).and()
                    .subject(Long.toString(userId))
                    .claim("token_use", "ADMIN_ACCESS")
                    .claim("auth_version", authVersion)
                    .id(UUID.randomUUID().toString())
                    .issuedAt(Date.from(now)).expiration(Date.from(expiresAt))
                    .signWith(key, Jwts.SIG.HS256).compact();
            return new AdminTokenDto(token, expiresAt, lifetime.toSeconds());
        } catch (JwtException | IllegalArgumentException exception) {
            throw new AdminTokenException(AdminTokenErrorCode.TOKEN_EXCEPTION);
        }
    }

    @Override
    public AdminTokenClaims validationTokenWithThrow(String token) {
        if (token == null || token.length() > 8192 || token.isBlank()) throw invalidToken();
        try {
            var signed = Jwts.parser().verifyWith(key)
                    .clock(() -> Date.from(clock.instant()))
                    .requireIssuer(ISSUER).require("token_use", "ADMIN_ACCESS")
                    .build().parseSignedClaims(token);
            Claims claims = signed.getPayload();
            Instant now = clock.instant();
            if (!"HS256".equals(signed.getHeader().getAlgorithm())
                    || !Set.of(ISSUER).equals(claims.getAudience())
                    || claims.containsKey("userId")
                    || claims.getExpiration() == null || claims.getIssuedAt() == null
                    || !claims.getExpiration().toInstant().isAfter(now)
                    || !claims.getExpiration().after(claims.getIssuedAt())
                    || claims.getIssuedAt().toInstant().isAfter(now.plusSeconds(10))
                    || claims.getSubject() == null || !claims.getSubject().matches("[1-9][0-9]{0,18}")) {
                throw invalidToken();
            }
            Object rawVersion = claims.get("auth_version");
            if (!(rawVersion instanceof Integer || rawVersion instanceof Long)) throw invalidToken();
            long version = ((Number) rawVersion).longValue();
            if (version < 0) throw invalidToken();
            return new AdminTokenClaims(Long.parseLong(claims.getSubject()), version);
        } catch (ExpiredJwtException exception) {
            throw new AdminTokenException(AdminTokenErrorCode.EXPIRED_TOKEN);
        } catch (JwtException | IllegalArgumentException exception) {
            throw invalidToken();
        }
    }

    private static AdminTokenException invalidToken() {
        return new AdminTokenException(AdminTokenErrorCode.INVALID_TOKEN);
    }
}
