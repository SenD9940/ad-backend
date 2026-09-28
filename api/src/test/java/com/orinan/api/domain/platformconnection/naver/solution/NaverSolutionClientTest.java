package com.orinan.api.domain.platformconnection.naver.solution;

import com.orinan.api.common.exception.ApiException;
import com.orinan.api.common.time.SeoulDateTimes;
import com.orinan.api.domain.platformconnection.naver.NaverCommerceClient;
import com.orinan.db.naverconnection.enums.NaverTokenType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Flow;

import static com.orinan.api.domain.platformconnection.naver.solution.NaverAuthorizationProvider.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class NaverSolutionClientTest {
    private NaverSolutionProperties properties;
    private NaverCommerceClient commerce;
    private NaverSolutionClient client;
    private final List<HttpRequest> requests = new ArrayList<>();
    private final Queue<Object> results = new ArrayDeque<>();
    private static final String PROOF = "private.JWE.+/=?do-not-log";
    private static final String TOKEN = "self-token-do-not-log";
    private static final String PROOF_JSON = """
            {"accountUid":"seller-1","solutionId":"solution-1","roleGroupType":"ACCOUNT",
             "channelName":"스토어","accountAuthentication":false,"approveSubscriptionYn":true,
             "status":"ON_EXAMINATION"}
            """;
    private static final String SUB_JSON = """
            {"accountUid":"seller-1","solutionId":"solution-1","subscriptionId":"subscription-1",
             "plan":{"id":"plan-1","name":"유료 플랜"},"status":"ON_EXAMINATION"}
            """;

    @BeforeEach @SuppressWarnings("unchecked") void setUp() throws Exception {
        properties = NaverSolutionPropertiesTest.configured();
        commerce = mock(NaverCommerceClient.class);
        when(commerce.issueToken(anyString(), anyString(), eq(NaverTokenType.SELF), isNull()))
                .thenReturn(new NaverCommerceClient.IssuedToken(TOKEN, SeoulDateTimes.now().plusHours(3)));
        HttpClient http = mock(HttpClient.class);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(invocation -> {
            requests.add(invocation.getArgument(0));
            Object result = results.remove();
            if (result instanceof Exception exception) throw exception;
            return result;
        });
        client = new NaverSolutionClient(properties, commerce, JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build(), http);
    }

    @Test void interpretsProofAndReadsStableSubscriptionBeforeReturningReview() {
        response(200, PROOF_JSON); response(200, SUB_JSON);
        var proof = client.interpretProof(PROOF);
        assertThat(proof.accountUid()).isEqualTo("seller-1");
        assertThat(proof.subscriptionId()).isEqualTo("subscription-1");
        assertThat(proof.planId()).isEqualTo("plan-1");
        assertThat(proof.planName()).isEqualTo("유료 플랜");
        assertThat(proof.authenticated()).isFalse();
        assertThat(proof.approvalAllowed()).isTrue();
        assertThat(proof.accountId()).isNull();
        assertThat(proof.storeUrl()).isNull();
        assertThat(requests).hasSize(2);
        assertThat(requests.get(0).uri().getRawQuery()).isEqualTo("token=private.JWE.%2B%2F%3D%3Fdo-not-log");
        assertThat(requests.get(1).uri().getPath()).endsWith("/subscriptions/seller-1");
        for (HttpRequest request : requests) {
            assertThat(request.method()).isEqualTo("GET");
            assertThat(request.headers().firstValue("Authorization")).contains("Bearer " + TOKEN);
        }
    }

    @Test void rejectsMissingLifecycleAndMalformedBooleansInsteadOfFabricatingReview() {
        for (String invalid : List.of(PROOF_JSON.replace("false", "\"false\""),
                PROOF_JSON.replace("true", "\"Y\""), PROOF_JSON.replace("solution-1", "wrong-solution"),
                PROOF_JSON.replace("\"ACCOUNT\"", "\"ACCOUNT_SUB\""))) {
            response(200, invalid);
            assertThatThrownBy(() -> client.interpretProof(PROOF)).isInstanceOf(ApiException.class).hasNoCause();
        }
        response(200, PROOF_JSON);
        response(200, SUB_JSON.replace("\"subscriptionId\":\"subscription-1\",", ""));
        assertThatThrownBy(() -> client.interpretProof(PROOF)).isInstanceOf(ApiException.class);
    }

    @Test void detectsSellerSolutionStatusAndPlanMismatch() {
        for (String invalid : List.of(SUB_JSON.replace("seller-1", "other-seller"),
                SUB_JSON.replace("solution-1", "other-solution"), SUB_JSON.replace("ON_EXAMINATION", "SUBSCRIBING"))) {
            response(200, PROOF_JSON); response(200, invalid);
            assertThatThrownBy(() -> client.interpretProof(PROOF)).isInstanceOf(ApiException.class);
        }
        response(200, PROOF_JSON.replace("\"status\":", "\"planId\":\"other-plan\",\"status\":"));
        response(200, SUB_JSON);
        assertThatThrownBy(() -> client.interpretProof(PROOF)).isInstanceOf(ApiException.class);
    }

    @Test void unknownAndPaymentFailedStatesNeverBecomeActiveAndTerminationReservationRemainsActive() {
        for (String state : List.of("UNRECOGNIZED", "ON_PAYMENT_FAIL")) {
            response(200, SUB_JSON.replace("ON_EXAMINATION", state));
            assertThat(client.getSubscription("seller-1").status()).isEqualTo("UNKNOWN");
        }
        response(200, SUB_JSON.replace("ON_EXAMINATION", "WAITING_UNSUBSCRIPTION"));
        assertThat(client.getSubscription("seller-1").status()).isEqualTo("ACTIVE");
        response(200, SUB_JSON.replace("ON_EXAMINATION", "UNSUBSCRIBED"));
        assertThat(client.getSubscription("seller-1").status()).isEqualTo("ENDED");
    }

    @Test void approvalUsesOnePutWithJweAndCamelCaseMappingThenReadsBackSameLifecycle() {
        approved(); active();
        var result = client.approve(PROOF, proof(), "mapping-1");
        assertThat(result.status()).isEqualTo("ACTIVE");
        assertThat(result.accountMappingId()).isEqualTo("mapping-1");
        assertThat(requests).hasSize(2);
        assertThat(requests.get(0).method()).isEqualTo("PUT");
        assertThat(requests.get(0).uri().getPath()).endsWith("/subscriptions/approve");
        assertThat(requests.get(0).uri().getRawQuery()).startsWith("token=");
        assertThat(body(requests.get(0))).isEqualTo("{\"accountMappingId\":\"mapping-1\"}");
        assertThat(requests.get(1).method()).isEqualTo("GET");
    }

    @Test void malformedAmbiguousApprovalAndTransportFailureNeverRetry() {
        for (Object failure : List.of(new IOException("private JWE transport message"),
                httpResponse(500, "private provider error"), httpResponse(408, "timeout"),
                httpResponse(200, "not json"), httpResponse(200, "{}"), httpResponse(204, ""))) {
            requests.clear(); results.add(failure);
            assertThatThrownBy(() -> client.approve(PROOF, proof(), "mapping-1"))
                    .isInstanceOf(OutcomeUnknownException.class).hasNoCause().hasMessageNotContaining(PROOF);
            assertThat(requests).hasSize(1);
        }
    }

    @Test void rejectionIsDefiniteAndDoesNotReadBackOrRetry() {
        response(403, "{\"message\":\"private server details\"}");
        assertThatThrownBy(() -> client.approve(PROOF, proof(), "mapping-1"))
                .isInstanceOf(ApiException.class).isNotInstanceOf(OutcomeUnknownException.class)
                .hasMessageNotContaining("private server details").hasNoCause();
        assertThat(requests).hasSize(1);
    }

    @Test void failedReadBackAndDifferentLifecycleAreUncertainWithoutSecondApproval() {
        for (String result : List.of(SUB_JSON.replace("subscription-1", "subscription-2"), "{}")) {
            requests.clear(); approved(); response(200, result);
            assertThatThrownBy(() -> client.approve(PROOF, proof(), "mapping-1"))
                    .isInstanceOf(OutcomeUnknownException.class);
            assertThat(requests.stream().filter(r -> r.method().equals("PUT"))).hasSize(1);
        }
        requests.clear(); approved(); response(503, "failed read");
        assertThatThrownBy(() -> client.approve(PROOF, proof(), "mapping-1"))
                .isInstanceOf(OutcomeUnknownException.class);
        assertThat(requests).hasSize(2);
    }

    @Test void disabledConfigurationAndDisallowedProofMakeNoProviderCalls() {
        assertThatThrownBy(() -> client.approve(PROOF,
                new SellerProof("seller-1", null, "스토어", null, "subscription-1", "플랜", false, false), "mapping-1"))
                .isInstanceOf(ApiException.class);
        properties.setEnabled(false);
        assertThatThrownBy(() -> client.interpretProof(PROOF)).isInstanceOf(ApiException.class);
        assertThat(requests).isEmpty(); verifyNoInteractions(commerce);
    }

    @Test void sellerCredentialScopeUsesOnlyVerifiedUid() {
        var token = new NaverCommerceClient.IssuedToken("seller-token", SeoulDateTimes.now().plusHours(3));
        when(commerce.issueToken("client-1", "mock-client-secret", NaverTokenType.SELLER, "seller-1")).thenReturn(token);
        assertThat(client.issueSellerToken("seller-1")).isSameAs(token);
        verify(commerce).issueToken("client-1", "mock-client-secret", NaverTokenType.SELLER, "seller-1");
        assertThat(requests).isEmpty();
    }

    @Test void providerExceptionsAndDebugLogsDoNotExposeProofOrToken(CapturedOutput output) {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("org.springframework");
        var previous = logger.getLevel(); logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        try {
            response(503, PROOF + TOKEN);
            assertThatThrownBy(() -> client.approve(PROOF, proof(), "mapping-1"))
                    .hasMessageNotContaining(PROOF).hasMessageNotContaining(TOKEN).hasNoCause();
            assertThat(output.getAll()).doesNotContain(PROOF, TOKEN);
        } finally { logger.setLevel(previous); }
    }

    private SellerProof proof() { return new SellerProof("seller-1", null, "스토어", null,
            "subscription-1", "유료 플랜", false, true, "plan-1"); }
    private void approved() { response(200, "{\"accountUid\":\"seller-1\",\"solutionId\":\"solution-1\",\"status\":\"SUBSCRIBING\",\"accountMappingId\":\"mapping-1\"}"); }
    private void active() { response(200, SUB_JSON.replace("ON_EXAMINATION", "SUBSCRIBING")
            .replace("\"subscriptionId\":", "\"accountMappingId\":\"mapping-1\",\"subscriptionId\":")); }
    private void response(int status, String body) { results.add(httpResponse(status, body)); }
    @SuppressWarnings("unchecked") private static HttpResponse<byte[]> httpResponse(int status, String body) {
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
        return response;
    }
    private static String body(HttpRequest request) {
        var bytes = new ByteArrayOutputStream();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
            public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            public void onNext(ByteBuffer buffer) { byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk); }
            public void onError(Throwable error) { throw new AssertionError(error); }
            public void onComplete() { }
        });
        return bytes.toString(StandardCharsets.UTF_8);
    }
}
