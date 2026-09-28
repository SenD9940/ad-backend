package com.orinan.api.domain.platformconnection.naver.solution;

import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.platformconnection.naver.NaverCommerceClient;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

@Component
public class NaverAuthorizationProvider {
    private final NaverSolutionProperties properties;
    private final NaverSolutionClient client;
    private final NaverMarketplaceJwtVerifier verifier;

    public NaverAuthorizationProvider(NaverSolutionProperties properties, NaverSolutionClient client,
                                      NaverMarketplaceJwtVerifier verifier) {
        this.properties = properties;
        this.client = client;
        this.verifier = verifier;
    }

    public boolean ready() { return properties.ready() && verifier.keyConfigured(); }
    public String applicationRef() { return properties.getApplicationRef(); }
    public String solutionId() { return properties.getSolutionId(); }
    public String publicBaseUrl() { return properties.getPublicBaseUrl(); }
    public String apiBaseUrl() { return publicBaseUrl(); }
    public String frontendBaseUrl() { return properties.getFrontendBaseUrl(); }
    public String marketplaceUrl() { return properties.getMarketplaceUrl(); }

    public URI launchUri(String state, String callbackUri) {
        requireReady();
        if (!validState(state)
                || !properties.callbackUrl().equals(callbackUri)) throw invalidProof();
        String uri = properties.getAuthorizationUrlTemplate()
                .replace("{state}", encode(state)).replace("{callback}", encode(callbackUri))
                .replace("{solutionId}", encode(solutionId()));
        if (!NaverSolutionProperties.naverUrl(uri)) throw unavailable();
        return URI.create(uri);
    }

    public Callback readCallback(Map<String, String> parameters) {
        requireReady();
        String state = parameters.get(properties.getCallbackStateParameter());
        String proof = parameters.get(properties.getCallbackProofParameter());
        if (!validState(state)
                || proof == null || proof.isBlank() || proof.length() > 32768) throw invalidProof();
        return new Callback(state, proof);
    }

    public MarketplaceProof verifyMarketplace(String jwt) {
        requireReady();
        return verifier.verify(jwt);
    }

    public String marketplaceToken(Map<String, String> parameters) {
        requireReady();
        return parameters.get(properties.getMarketplaceTokenParameter());
    }

    public SellerProof interpretProof(String jwe) { requireReady(); return client.interpretProof(jwe); }
    public Subscription querySubscription(String accountUid) { requireReady(); return client.getSubscription(accountUid); }
    public Subscription approve(String jwe, SellerProof proof, String accountMappingId) {
        requireReady(); return client.approve(jwe, proof, accountMappingId);
    }
    public NaverCommerceClient.IssuedToken issueSellerToken(String accountUid) {
        requireReady(); return client.issueSellerToken(accountUid);
    }
    public NaverCommerceClient.SellerAccount getSellerAccount(String token) {
        requireReady(); return client.getSellerAccount(token);
    }

    public void requireReady() { if (!ready()) throw unavailable(); }
    public static ApiException unavailable() {
        return new ApiException(ApiCode.BAD_REQUEST, "네이버 솔루션 등록과 인증 연동 설정이 아직 준비되지 않았습니다.");
    }
    static ApiException invalidProof() {
        return new ApiException(ApiCode.BAD_REQUEST, "네이버 판매자 인증을 확인할 수 없습니다. 인증을 다시 시작해 주세요.");
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static boolean validState(String value) {
        return value != null && (value.matches("[A-Za-z0-9_-]{32,256}")
                || value.matches("[A-Za-z0-9_-]{32,64}\\.[A-Za-z0-9_-]{32,64}"));
    }

    public record Callback(String state, String jwe) {
        @Override public String toString() { return "Callback[REDACTED]"; }
    }
    public record MarketplaceProof(String accountUid, Instant expiresAt) { }
    public record SellerProof(String accountUid, String accountId, String sellerName, String storeUrl,
                              String subscriptionId, String planName, boolean authenticated,
                              boolean approvalAllowed, String planId) {
        public SellerProof(String accountUid, String accountId, String sellerName, String storeUrl,
                           String subscriptionId, String planName, boolean authenticated, boolean approvalAllowed) {
            this(accountUid, accountId, sellerName, storeUrl, subscriptionId, planName, authenticated, approvalAllowed, null);
        }
    }
    public record Subscription(String subscriptionId, String status, String planName,
                               String accountMappingId, String accountUid, String planId) {
        public Subscription(String subscriptionId, String status, String planName, String accountMappingId) {
            this(subscriptionId, status, planName, accountMappingId, null, null);
        }
        public String providerSubscriptionId() { return subscriptionId; }
    }

    public static final class OutcomeUnknownException extends ApiException {
        public OutcomeUnknownException() {
            super(ApiCode.SERVER_ERROR, "네이버 구독 승인 결과를 확인하고 있습니다. 승인 요청을 다시 보내지 마세요.");
        }
    }
}
