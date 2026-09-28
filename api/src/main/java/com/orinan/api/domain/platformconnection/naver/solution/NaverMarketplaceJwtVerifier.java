package com.orinan.api.domain.platformconnection.naver.solution;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.springframework.stereotype.Component;

import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Set;

@Component
public class NaverMarketplaceJwtVerifier {
    private final NaverSolutionProperties properties;

    public NaverMarketplaceJwtVerifier(NaverSolutionProperties properties) { this.properties = properties; }

    public boolean keyConfigured() {
        try { publicKey(); return true; }
        catch (Exception exception) { return false; }
    }

    public NaverAuthorizationProvider.MarketplaceProof verify(String jwt) {
        try {
            if (jwt == null || jwt.isBlank() || jwt.length() > 32768) throw new IllegalArgumentException();
            var signed = Jwts.parser().verifyWith(publicKey()).requireIssuer("merc").requireSubject("SELLER_INFO")
                    .sig().clear().add(Jwts.SIG.RS256).and().build().parseSignedClaims(jwt);
            if (!"RS256".equals(signed.getHeader().getAlgorithm())
                    || signed.getHeader().containsKey("zip")) throw new IllegalArgumentException();
            Claims claims = signed.getPayload();
            Instant now = Instant.now();
            Date issued = claims.getIssuedAt();
            Date expires = claims.getExpiration();
            if (issued == null || expires == null || issued.toInstant().isAfter(now)
                    || !expires.toInstant().isAfter(now) || !expires.after(issued)
                    || !properties.getSolutionId().equals(claims.get("solutionId", String.class))) {
                throw new IllegalArgumentException();
            }
            String accountUid = claims.get("accountUid", String.class);
            String role = claims.get("roleGroupType", String.class);
            if (accountUid == null || accountUid.isBlank() || accountUid.length() > 255
                    || !Set.of("REPRESENT", "MANAGER_GROUP", "ACCOUNT").contains(role)) throw new IllegalArgumentException();
            return new NaverAuthorizationProvider.MarketplaceProof(accountUid, expires.toInstant());
        } catch (Exception exception) {
            // No provider claims, signature, public key or raw proof in exceptions/logs.
            throw NaverAuthorizationProvider.invalidProof();
        }
    }

    private RSAPublicKey publicKey() throws Exception {
        String configured = properties.getJwtPublicKey();
        if (configured == null || configured.length() > 16384) throw new IllegalArgumentException();
        String base64 = configured.replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "").replaceAll("\\s", "");
        RSAPublicKey key = (RSAPublicKey) KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64)));
        if (key.getModulus().bitLength() < 2048) throw new IllegalArgumentException();
        return key;
    }
}
