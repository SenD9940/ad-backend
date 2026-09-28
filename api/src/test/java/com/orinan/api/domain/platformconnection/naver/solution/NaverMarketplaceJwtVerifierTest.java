package com.orinan.api.domain.platformconnection.naver.solution;

import com.orinan.api.common.exception.ApiException;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.security.KeyPair;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class NaverMarketplaceJwtVerifierTest {
    private KeyPair key;
    private NaverSolutionProperties properties;
    private NaverMarketplaceJwtVerifier verifier;

    @BeforeEach void setUp() {
        key = Jwts.SIG.RS256.keyPair().build();
        properties = NaverSolutionPropertiesTest.configured();
        properties.setJwtPublicKey(Base64.getEncoder().encodeToString(key.getPublic().getEncoded()));
        verifier = new NaverMarketplaceJwtVerifier(properties);
    }

    @Test void acceptsSignedMatchingSellerWithinLifetime() {
        String jwt = token("solution-1", "ACCOUNT", Instant.now().minusSeconds(1), Instant.now().plusSeconds(60));
        var proof = verifier.verify(jwt);
        assertThat(proof.accountUid()).isEqualTo("seller-1");
        assertThat(proof.expiresAt()).isAfter(Instant.now());
        assertThat(verifier.keyConfigured()).isTrue();
    }

    @Test void rejectsWrongSolutionRoleExpirationAndFutureIssueTime() {
        for (String jwt : new String[] {
                token("other-solution", "ACCOUNT", Instant.now().minusSeconds(1), Instant.now().plusSeconds(60)),
                token("solution-1", "ACCOUNT_SUB", Instant.now().minusSeconds(1), Instant.now().plusSeconds(60)),
                token("solution-1", "ACCOUNT", Instant.now().minusSeconds(120), Instant.now().minusSeconds(1)),
                token("solution-1", "ACCOUNT", Instant.now().plusSeconds(60), Instant.now().plusSeconds(120)) }) {
            assertThatThrownBy(() -> verifier.verify(jwt)).isInstanceOf(ApiException.class)
                    .hasNoCause().hasMessageNotContaining(jwt);
        }
    }

    @Test void rejectsMissingDatesWrongIssuerSubjectAlgorithmAndKey() {
        String[] invalid = {
                Jwts.builder().issuer("merc").subject("SELLER_INFO").claims(claims()).signWith(key.getPrivate(), Jwts.SIG.RS256).compact(),
                Jwts.builder().issuer("other").subject("SELLER_INFO").claims(claims()).issuedAt(new Date()).expiration(Date.from(Instant.now().plusSeconds(60))).signWith(key.getPrivate(), Jwts.SIG.RS256).compact(),
                Jwts.builder().issuer("merc").subject("OTHER").claims(claims()).issuedAt(new Date()).expiration(Date.from(Instant.now().plusSeconds(60))).signWith(key.getPrivate(), Jwts.SIG.RS256).compact(),
                Jwts.builder().issuer("merc").subject("SELLER_INFO").claims(claims()).issuedAt(new Date()).expiration(Date.from(Instant.now().plusSeconds(60))).signWith(key.getPrivate(), Jwts.SIG.RS512).compact(),
                Jwts.builder().issuer("merc").subject("SELLER_INFO").claims(claims()).issuedAt(new Date()).expiration(Date.from(Instant.now().plusSeconds(60))).signWith(Jwts.SIG.RS256.keyPair().build().getPrivate(), Jwts.SIG.RS256).compact()
        };
        for (String jwt : invalid) assertThatThrownBy(() -> verifier.verify(jwt)).isInstanceOf(ApiException.class).hasNoCause();
    }

    @Test void invalidKeyFailsReadinessWithoutExposingConfiguration() {
        properties.setJwtPublicKey("secret-looking-bad-key");
        assertThat(verifier.keyConfigured()).isFalse();
        var provider = new NaverAuthorizationProvider(properties, mock(NaverSolutionClient.class), verifier);
        assertThat(provider.ready()).isFalse();
        assertThatThrownBy(() -> provider.launchUri("a".repeat(43), properties.callbackUrl()))
                .isInstanceOf(ApiException.class).hasMessageNotContaining("secret-looking-bad-key");
    }

    @Test void launchesOnlyExactConfiguredCallbackAndMappedOpaqueState() {
        var provider = new NaverAuthorizationProvider(properties, mock(NaverSolutionClient.class), verifier);
        String state = "a".repeat(43) + "." + "b".repeat(43);
        assertThat(provider.launchUri(state, properties.callbackUrl()).getRawQuery())
                .contains("nonce=" + state, "redirect=https%3A%2F%2Fapp.example.test%2Fopen-api%2Fintegrations%2Fnaver%2Fcallback");
        assertThatThrownBy(() -> provider.launchUri(state, "https://evil.test/callback")).isInstanceOf(ApiException.class);
        var callback = provider.readCallback(Map.of("request_state", state, "seller_proof", "secret-jwe"));
        assertThat(callback.state()).isEqualTo(state);
        assertThat(callback.jwe()).isEqualTo("secret-jwe");
        assertThat(callback.toString()).doesNotContain("secret-jwe", state);
        assertThatThrownBy(() -> provider.readCallback(Map.of("state", state, "token", "secret-jwe")))
                .isInstanceOf(ApiException.class);
    }

    private Map<String, Object> claims() {
        return Map.of("solutionId", "solution-1", "accountUid", "seller-1", "roleGroupType", "ACCOUNT");
    }
    private String token(String solution, String role, Instant issued, Instant expires) {
        return Jwts.builder().issuer("merc").subject("SELLER_INFO")
                .claim("solutionId", solution).claim("accountUid", "seller-1").claim("roleGroupType", role)
                .issuedAt(Date.from(issued)).expiration(Date.from(expires)).signWith(key.getPrivate(), Jwts.SIG.RS256).compact();
    }
}
