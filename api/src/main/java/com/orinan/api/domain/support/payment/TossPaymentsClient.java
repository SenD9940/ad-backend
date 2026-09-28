package com.orinan.api.domain.support.payment;

import jakarta.annotation.PreDestroy;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Fixed-host Toss client. Approval is sent once; reconciliation uses only GET. */
@Component
public class TossPaymentsClient {
    private static final String BASE_URL = "https://api.tosspayments.com";
    private static final int MAX_RESPONSE_BYTES = 128 * 1024;
    private final RestClient client;
    private final TossPaymentProperties properties;
    private final JsonMapper mapper;
    private final HttpComponentsClientHttpRequestFactory requestFactory;

    @Autowired
    public TossPaymentsClient(TossPaymentProperties properties, JsonMapper mapper) {
        this.properties = properties;
        this.mapper = json(mapper);
        this.requestFactory = requestFactory();
        this.client = RestClient.builder().requestFactory(requestFactory).build();
    }

    TossPaymentsClient(RestClient client, TossPaymentProperties properties, JsonMapper mapper) {
        this.client = client;
        this.properties = properties;
        this.mapper = json(mapper);
        this.requestFactory = null;
    }

    public PaymentSnapshot confirm(String paymentKey, String orderId, long amount) {
        configured();
        orderId(orderId);
        paymentKey(paymentKey);
        if (amount <= 0) {
            throw new TossPaymentException("결제 승인 요청을 확인해 주세요.", false, null);
        }
        String body = mapper.writeValueAsString(Map.of("paymentKey", paymentKey, "orderId", orderId, "amount", amount));
        return execute(client.post().uri(BASE_URL + "/v1/payments/confirm")
                .headers(this::authentication).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "support-confirm-" + orderId).body(body));
    }

    public PaymentSnapshot getByPaymentKey(String paymentKey) {
        configured();
        paymentKey(paymentKey);
        // Widget gsk credentials explicitly support paymentKey lookup. The bound key is loaded
        // from our encrypted order record, never accepted from an unauthenticated webhook.
        var uri = UriComponentsBuilder.fromUriString(BASE_URL).pathSegment("v1", "payments", "{paymentKey}")
                .encode().buildAndExpand(paymentKey).toUri();
        return execute(client.get().uri(uri)
                .headers(this::authentication));
    }

    private PaymentSnapshot execute(RestClient.RequestHeadersSpec<?> request) {
        try {
            return request.exchange((ignored, response) -> {
                int status = response.getStatusCode().value();
                byte[] body = response.getBody().readNBytes(MAX_RESPONSE_BYTES + 1);
                if (!response.getStatusCode().is2xxSuccessful()) {
                    boolean ambiguous = status >= 500 || status < 400 || status == 408 || status == 409
                            || status == 425 || status == 429 || alreadyProcessed(body);
                    throw new TossPaymentException(ambiguous
                            ? "결제 결과를 확인하고 있습니다. 결제 내역을 다시 조회해 주세요."
                            : "결제 요청을 처리하지 못했습니다. 결제 정보를 확인해 주세요.", ambiguous, status);
                }
                if (body.length > MAX_RESPONSE_BYTES) throw invalidResponse(status);
                return snapshot(body, status);
            });
        } catch (TossPaymentException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            // Transport/decoding exceptions can contain headers, payment keys and the response body.
            throw new TossPaymentException("결제 결과를 확인할 수 없습니다. 결제 내역을 다시 조회해 주세요.", true, null);
        }
    }

    private PaymentSnapshot snapshot(byte[] body, int status) {
        try {
            JsonNode root = mapper.readTree(body);
            if (root == null || !root.isObject()) throw invalidResponse(status);
            return new PaymentSnapshot(text(root, "orderId", 64), text(root, "paymentKey", 200),
                    text(root, "status", 40), text(root, "currency", 10),
                    amount(root, "totalAmount"), amount(root, "balanceAmount"));
        } catch (RuntimeException exception) {
            throw invalidResponse(status);
        }
    }

    private boolean alreadyProcessed(byte[] body) {
        if (body.length > MAX_RESPONSE_BYTES) return false;
        try {
            return "ALREADY_PROCESSED_PAYMENT".equals(mapper.readTree(body).path("code").asString());
        } catch (RuntimeException ignored) { return false; }
    }

    private String text(JsonNode root, String field, int maximum) {
        JsonNode value = root.path(field);
        if (!value.isString() || value.asString().isBlank() || value.asString().length() > maximum) {
            throw invalidResponse(200);
        }
        return value.asString();
    }

    private long amount(JsonNode root, String field) {
        JsonNode value = root.path(field);
        if (!value.isNumber()) throw invalidResponse(200);
        return new BigDecimal(value.asString()).longValueExact();
    }

    private void authentication(HttpHeaders headers) {
        headers.setBasicAuth(properties.getSecretKey(), "", StandardCharsets.UTF_8);
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
    }

    private void configured() {
        if (!properties.isConfigured()) throw new TossPaymentException("결제 서비스가 아직 설정되지 않았습니다.", false, null);
    }

    private void orderId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{6,64}")) {
            throw new TossPaymentException("결제 주문번호를 확인해 주세요.", false, null);
        }
    }

    private void paymentKey(String value) {
        if (value == null || value.isBlank() || value.length() > 200 || value.equals(".") || value.equals("..")
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new TossPaymentException("결제 식별 정보를 확인해 주세요.", false, null);
        }
    }

    private TossPaymentException invalidResponse(int status) {
        return new TossPaymentException("결제 응답을 확인할 수 없습니다. 결제 내역을 다시 조회해 주세요.", true, status);
    }

    private static JsonMapper json(JsonMapper mapper) {
        return mapper.rebuild().enable(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES).build();
    }

    private static HttpComponentsClientHttpRequestFactory requestFactory() {
        var manager = PoolingHttpClientConnectionManagerBuilder.create().setDefaultConnectionConfig(
                ConnectionConfig.custom().setConnectTimeout(Timeout.ofSeconds(3)).build()).build();
        var http = HttpClients.custom().setConnectionManager(manager).disableAutomaticRetries().disableRedirectHandling()
                .setDefaultRequestConfig(RequestConfig.custom().setConnectionRequestTimeout(Timeout.ofSeconds(3))
                        .setResponseTimeout(Timeout.ofSeconds(10)).build()).build();
        return new HttpComponentsClientHttpRequestFactory(http);
    }

    @PreDestroy
    public void close() throws Exception {
        if (requestFactory != null) requestFactory.destroy();
    }

    public record PaymentSnapshot(String orderId, String paymentKey, String status, String currency,
                                  long totalAmount, long balanceAmount) {
        @Override public String toString() { return "TossPaymentSnapshot[REDACTED]"; }
    }
}
