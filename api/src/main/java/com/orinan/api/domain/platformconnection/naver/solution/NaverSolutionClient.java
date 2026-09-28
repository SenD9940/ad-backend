package com.orinan.api.domain.platformconnection.naver.solution;

import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.platformconnection.naver.NaverCommerceClient;
import com.orinan.db.naverconnection.enums.NaverTokenType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static com.orinan.api.domain.platformconnection.naver.solution.NaverAuthorizationProvider.*;

/** The published v2.89.0 Commerce API schemas use camelCase. Approval is never retried. */
@Component
public class NaverSolutionClient {
    private static final String BASE_URL = "https://api.commerce.naver.com/external";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private final NaverSolutionProperties properties;
    private final NaverCommerceClient commerce;
    private final JsonMapper mapper;
    private final HttpClient http;

    @Autowired
    public NaverSolutionClient(NaverSolutionProperties properties, NaverCommerceClient commerce, JsonMapper mapper) {
        this(properties, commerce, mapper, HttpClient.newBuilder().connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER).build());
    }

    NaverSolutionClient(NaverSolutionProperties properties, NaverCommerceClient commerce,
                        JsonMapper mapper, HttpClient http) {
        this.properties = properties;
        this.commerce = commerce;
        this.mapper = mapper;
        this.http = http;
    }

    public SellerProof interpretProof(String jwe) {
        requireConfigured();
        requireProof(jwe);
        JsonNode body = send(request("/v1/commerce-solutions/seller-info-by-token?token=" + encode(jwe)).GET().build(), false);
        String accountUid = required(body, "accountUid", 255);
        requireSolution(body);
        String role = required(body, "roleGroupType", 50);
        if (!Set.of("REPRESENT", "MANAGER_GROUP", "ACCOUNT").contains(role)) throw invalidProof();
        boolean authenticated = bool(body, "accountAuthentication");
        boolean approvalAllowed = bool(body, "approveSubscriptionYn");
        String proofStatus = status(required(body, "status", 100));
        Subscription subscription = getSubscription(accountUid);
        if (!proofStatus.equals(subscription.status())) throw invalidResponse();
        String proofPlan = optional(body, "planId", 255);
        if (proofPlan != null && !proofPlan.equals(subscription.planId())) throw invalidResponse();
        return new SellerProof(accountUid, null, required(body, "channelName", 255), null,
                subscription.subscriptionId(), subscription.planName(), authenticated, approvalAllowed,
                subscription.planId());
    }

    public Subscription getSubscription(String accountUid) {
        requireConfigured();
        identifier(accountUid);
        JsonNode body = send(request("/v1/commerce-solutions/subscriptions/" + encode(accountUid)).GET().build(), false);
        requireSolution(body);
        if (!accountUid.equals(required(body, "accountUid", 255))) throw invalidResponse();
        String canonicalStatus = status(required(body, "status", 100));
        String planId = null;
        String planName = null;
        JsonNode plan = body.path("plan");
        if (!plan.isMissingNode() && !plan.isNull()) {
            planId = required(plan, "id", 255);
            planName = required(plan, "name", 255);
        }
        // A stable provider lifecycle ID is mandatory, including pending applications.
        return new Subscription(required(body, "subscriptionId", 255), canonicalStatus, planName,
                optional(body, "accountMappingId", 255), accountUid, planId);
    }

    public Subscription approve(String jwe, SellerProof proof, String accountMappingId) {
        requireConfigured();
        requireProof(jwe);
        identifier(accountMappingId);
        if (proof == null || !proof.approvalAllowed()) throw invalidProof();
        identifier(proof.accountUid());
        identifier(proof.subscriptionId());
        // Token acquisition happens before the write boundary and cannot approve a subscription.
        HttpRequest request = request("/v1/commerce-solutions/subscriptions/approve?token=" + encode(jwe))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(Map.of("accountMappingId", accountMappingId))))
                .build();
        JsonNode approved = send(request, true);
        try {
            requireSolution(approved);
            if (!proof.accountUid().equals(required(approved, "accountUid", 255))
                    || !accountMappingId.equals(required(approved, "accountMappingId", 255))
                    || !"ACTIVE".equals(status(required(approved, "status", 100)))) throw invalidResponse();
            Subscription current = getSubscription(proof.accountUid());
            if (!proof.subscriptionId().equals(current.subscriptionId())
                    || !accountMappingId.equals(current.accountMappingId()) || !"ACTIVE".equals(current.status())) {
                throw invalidResponse();
            }
            return current;
        } catch (RuntimeException exception) { throw new OutcomeUnknownException(); }
    }

    public NaverCommerceClient.IssuedToken issueSellerToken(String accountUid) {
        requireConfigured();
        identifier(accountUid);
        return commerce.issueToken(properties.getClientId(), properties.getClientSecret(), NaverTokenType.SELLER, accountUid);
    }

    public NaverCommerceClient.SellerAccount getSellerAccount(String accessToken) {
        requireConfigured();
        return commerce.getSellerAccount(accessToken);
    }

    private HttpRequest.Builder request(String path) {
        var token = commerce.issueToken(properties.getClientId(), properties.getClientSecret(), NaverTokenType.SELF, null);
        return HttpRequest.newBuilder(URI.create(BASE_URL + path)).timeout(TIMEOUT)
                .header("Authorization", "Bearer " + token.accessToken()).header("Accept", "application/json");
    }

    private JsonNode send(HttpRequest request, boolean approval) {
        try {
            // JDK transport avoids WebClient's DEBUG request-URL log, which would expose the required JWE query.
            // Never enable jdk.httpclient.HttpClient.log for this integration.
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                if (approval && (status == 408 || status >= 500)) throw new OutcomeUnknownException();
                throw new ApiException(status >= 500 || status == 429 ? ApiCode.SERVER_ERROR : ApiCode.BAD_REQUEST,
                        "네이버 요청을 처리하지 못했습니다. 구독 신청과 판매자 권한을 확인해 주세요.");
            }
            byte[] bytes = response.body();
            if (bytes == null || bytes.length == 0 || bytes.length > 1024 * 1024) {
                if (approval) throw new OutcomeUnknownException();
                throw invalidResponse();
            }
            JsonNode node;
            try { node = mapper.readTree(bytes); }
            catch (RuntimeException exception) {
                if (approval) throw new OutcomeUnknownException();
                throw invalidResponse();
            }
            if (!node.isObject()) {
                if (approval) throw new OutcomeUnknownException();
                throw invalidResponse();
            }
            return node;
        } catch (ApiException exception) { throw exception; }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (approval) throw new OutcomeUnknownException();
            throw unavailableResponse();
        } catch (Exception exception) {
            if (approval) throw new OutcomeUnknownException();
            throw unavailableResponse();
        }
    }

    private void requireConfigured() { if (!properties.ready()) throw unavailable(); }
    private void requireSolution(JsonNode node) {
        if (!properties.getSolutionId().equals(required(node, "solutionId", 255))) throw invalidResponse();
    }
    private static void identifier(String value) {
        if (value == null || value.isBlank() || value.length() > 255 || value.chars().anyMatch(Character::isISOControl))
            throw invalidProof();
    }
    private static void requireProof(String value) {
        if (value == null || value.isBlank() || value.length() > 32768) throw invalidProof();
    }
    private static String status(String raw) {
        return switch (raw) {
            case "SUBSCRIBING", "WAITING_UNSUBSCRIPTION" -> "ACTIVE";
            case "WAITING_SUBSCRIPTION", "ON_EXAMINATION" -> "PENDING";
            case "UNSUBSCRIBED", "CANCEL_SUBSCRIPTION" -> "ENDED";
            default -> "UNKNOWN";
        };
    }
    private static boolean bool(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isBoolean()) throw invalidResponse();
        return value.asBoolean();
    }
    private static String optional(JsonNode node, String field, int max) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) return null;
        if (!value.isString() || value.asString().length() > max) throw invalidResponse();
        return value.asString();
    }
    private static String required(JsonNode node, String field, int max) {
        String value = optional(node, field, max);
        if (value == null || value.isBlank()) throw invalidResponse();
        return value;
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static ApiException invalidResponse() {
        return new ApiException(ApiCode.SERVER_ERROR, "네이버 구독 정보를 확인할 수 없습니다. 인증을 다시 진행해 주세요.");
    }
    private static ApiException unavailableResponse() {
        return new ApiException(ApiCode.SERVER_ERROR, "네이버 서버와 통신하지 못했습니다. 잠시 후 상태를 확인해 주세요.");
    }
}
