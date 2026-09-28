package com.orinan.api.domain.support.payment;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class TossPaymentsClientTest {
    private static final String ORDER = "support_order_123";
    private static final String KEY = "private-payment-key";
    private static final String SECRET = "test_sk_private-secret";
    private TossPaymentProperties properties;
    private TossPaymentsClient client;
    private MockRestServiceServer server;

    @BeforeEach void setUp() {
        properties = new TossPaymentProperties();
        properties.setClientKey("test_gck_public"); properties.setSecretKey(SECRET);
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new TossPaymentsClient(builder.build(), properties,
                JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build());
    }
    @AfterEach void verify() { server.verify(); }

    @Test void confirmsWithCamelCaseJsonBasicSecretAndStableIdempotencyHeader() {
        server.expect(requestTo("https://api.tosspayments.com/v1/payments/confirm"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Basic " + Base64.getEncoder().encodeToString((SECRET + ":").getBytes(StandardCharsets.UTF_8))))
                .andExpect(header("Idempotency-Key", "support-confirm-" + ORDER))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"paymentKey\":\"" + KEY + "\",\"orderId\":\"" + ORDER + "\",\"amount\":9900}"))
                .andRespond(withSuccess(snapshot(), MediaType.APPLICATION_JSON));
        var result = client.confirm(KEY, ORDER, 9900);
        assertThat(result.orderId()).isEqualTo(ORDER);
        assertThat(result.paymentKey()).isEqualTo(KEY);
        assertThat(result.status()).isEqualTo("DONE");
        assertThat(result.currency()).isEqualTo("KRW");
        assertThat(result.totalAmount()).isEqualTo(9900);
        assertThat(result.balanceAmount()).isEqualTo(9900);
        assertThat(result.toString()).doesNotContain(KEY, SECRET);
        assertThat(properties.toString()).doesNotContain(SECRET);
    }

    @Test void queriesTheStoredPaymentKeyOnTheFixedHostWithWidgetCredentials() {
        server.expect(requestTo("https://api.tosspayments.com/v1/payments/" + KEY))
                .andExpect(method(HttpMethod.GET)).andExpect(headerDoesNotExist("Idempotency-Key"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Basic " + Base64.getEncoder().encodeToString((SECRET + ":").getBytes(StandardCharsets.UTF_8))))
                .andRespond(withSuccess(snapshot(), MediaType.APPLICATION_JSON));
        assertThat(client.getByPaymentKey(KEY).status()).isEqualTo("DONE");
    }

    @Test void missingConfigurationAndInvalidOrderNeverSendRequests() {
        properties.setSecretKey("");
        assertThat(properties.isConfigured()).isFalse();
        assertThatThrownBy(() -> client.confirm(KEY, ORDER, 9900)).isInstanceOf(TossPaymentException.class);
        properties.setSecretKey(SECRET);
        for (String order : new String[]{"short", "../payments", "https://other.example/", "x".repeat(65)}) {
            assertThatThrownBy(() -> client.confirm(KEY, order, 9900)).isInstanceOf(TossPaymentException.class);
        }
        for (String key : new String[]{"", " ", ".", "..", "private\nkey", "x".repeat(201)}) {
            assertThatThrownBy(() -> client.getByPaymentKey(key)).isInstanceOf(TossPaymentException.class);
        }
    }

    @Test void paymentKeyLookupEncodesOnePathSegmentAndNeverAcceptsAnExternalHost() {
        server.expect(requestTo("https://api.tosspayments.com/v1/payments/private%2Fkey%2Bvalue%3Fq%23fragment"))
                .andRespond(withSuccess(snapshot(), MediaType.APPLICATION_JSON));
        assertThat(client.getByPaymentKey("private/key+value?q#fragment").status()).isEqualTo("DONE");
    }

    @Test void lookupFailureDropsTheExceptionContainingItsPaymentKeyUrl() {
        server.expect(requestTo("https://api.tosspayments.com/v1/payments/" + KEY))
                .andRespond(withException(new IOException("GET https://api.tosspayments.com/v1/payments/" + KEY)));
        assertThatThrownBy(() -> client.getByPaymentKey(KEY)).isInstanceOf(TossPaymentException.class)
                .hasNoCause().hasMessageNotContaining(KEY).hasMessageNotContaining(SECRET);
    }

    @Test void definitiveRejectionIsSafeAndNotRetried() {
        server.expect(requestTo("https://api.tosspayments.com/v1/payments/confirm"))
                .andRespond(withBadRequest().body("{\"code\":\"INVALID_REQUEST\",\"message\":\"" + SECRET + " " + KEY + "\"}"));
        assertThatThrownBy(() -> client.confirm(KEY, ORDER, 9900)).isInstanceOfSatisfying(TossPaymentException.class, error -> {
            assertThat(error.isAmbiguous()).isFalse(); assertThat(error.getHttpStatus()).isEqualTo(400);
            assertThat(error).hasNoCause().hasMessageNotContaining(SECRET).hasMessageNotContaining(KEY);
        });
    }

    @ParameterizedTest @ValueSource(ints = {302, 408, 409, 429, 500, 502, 503})
    void uncertainHttpStatusesNeverTriggerAnApprovalRetry(int status) {
        server.expect(requestTo("https://api.tosspayments.com/v1/payments/confirm"))
                .andRespond(withStatus(HttpStatus.valueOf(status)).body(SECRET + " " + KEY));
        assertThatThrownBy(() -> client.confirm(KEY, ORDER, 9900)).isInstanceOfSatisfying(TossPaymentException.class, error -> {
            assertThat(error.isAmbiguous()).isTrue(); assertThat(error.getHttpStatus()).isEqualTo(status);
            assertThat(error).hasNoCause().hasMessageNotContaining(SECRET).hasMessageNotContaining(KEY);
        });
    }

    @Test void alreadyProcessedRequiresReconciliationEvenThoughItUsesHttp400() {
        server.expect(requestTo("https://api.tosspayments.com/v1/payments/confirm"))
                .andRespond(withBadRequest().body("{\"code\":\"ALREADY_PROCESSED_PAYMENT\"}"));
        assertThatThrownBy(() -> client.confirm(KEY, ORDER, 9900)).isInstanceOfSatisfying(TossPaymentException.class,
                error -> assertThat(error.isAmbiguous()).isTrue());
    }

    @Test void transportFailureIsAmbiguousAndDropsTheOriginalException() {
        server.expect(requestTo("https://api.tosspayments.com/v1/payments/confirm"))
                .andRespond(withException(new IOException("timeout " + SECRET + " " + KEY)));
        assertThatThrownBy(() -> client.confirm(KEY, ORDER, 9900)).isInstanceOfSatisfying(TossPaymentException.class, error -> {
            assertThat(error.isAmbiguous()).isTrue(); assertThat(error.getHttpStatus()).isNull();
            assertThat(error).hasNoCause().hasMessageNotContaining(SECRET).hasMessageNotContaining(KEY);
        });
    }

    @ParameterizedTest @ValueSource(strings = {"", "not-json", "null", "[]", "{}",
            "{\"paymentKey\":\"p\",\"orderId\":\"order123\",\"status\":\"DONE\",\"currency\":\"KRW\",\"totalAmount\":9900.1,\"balanceAmount\":9900}",
            "{\"paymentKey\":\"p\",\"orderId\":\"order123\",\"status\":\"DONE\",\"currency\":\"KRW\",\"totalAmount\":9223372036854775808,\"balanceAmount\":9900}"})
    void malformedSuccessCannotBecomeAConfirmedPayment(String body) {
        server.expect(requestTo("https://api.tosspayments.com/v1/payments/confirm"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.confirm(KEY, ORDER, 9900)).isInstanceOfSatisfying(TossPaymentException.class,
                error -> assertThat(error.isAmbiguous()).isTrue()).hasNoCause();
    }

    @Test void numericStringsAreNotAcceptedAsPaymentAmounts() {
        server.expect(requestTo("https://api.tosspayments.com/v1/payments/confirm"))
                .andRespond(withSuccess(snapshot().replace("\"totalAmount\":9900", "\"totalAmount\":\"9900\""), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.confirm(KEY, ORDER, 9900)).isInstanceOf(TossPaymentException.class);
    }

    private String snapshot() {
        return "{\"orderId\":\"" + ORDER + "\",\"paymentKey\":\"" + KEY
                + "\",\"status\":\"DONE\",\"currency\":\"KRW\",\"totalAmount\":9900,\"balanceAmount\":9900,\"extra\":true}";
    }
}
