package com.orinan.api.domain.navercommerce.client;

import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.platformconnection.naver.NaverCommerceClient.AuthenticationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class NaverOrderClientTest {
    private static final String TOKEN = "PRIVATE-NAVER-TOKEN";
    private static final String PRIVATE = "PRIVATE-BUYER-NAME-ADDRESS-PHONE";
    private static final String ID = "2026092900000001";
    private static final String BASE = "https://api.commerce.naver.com/external";
    private static final String ORDERS = "/v1/pay-order/seller/product-orders";
    private static final OffsetDateTime FROM = OffsetDateTime.parse("2026-09-29T00:00:00+09:00");
    private static final OffsetDateTime TO = FROM.plusDays(1).minusNanos(1_000_000);
    private final JsonMapper json = JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();
    private final Queue<Mono<ClientResponse>> responses = new ArrayDeque<>();
    private final List<ClientRequest> requests = new ArrayList<>();
    private final AtomicLong clock = new AtomicLong(1);
    private NaverOrderClient client;

    @BeforeEach void setUp() {
        client = new NaverOrderClient(WebClient.builder().exchangeFunction(request -> {
            requests.add(request);
            return responses.remove();
        }), json, new NaverReadExecutor(clock::get, () -> Instant.parse("2026-09-29T00:00:00Z"), clock::addAndGet));
    }

    @Test void orderReadEncodesSeoulOffsetExactlyOnceAndUsesProviderCamelCaseQuery() {
        respond(HttpStatus.OK, "{\"data\":{\"contents\":[],\"pagination\":{\"page\":2}}}");
        var result = client.orders("app", TOKEN, FROM, TO, "ORDERED_DATETIME", "PAYED", 2, 50, deadline());
        assertThat(result.path("data").path("pagination").path("page").asInt()).isEqualTo(2);
        var request = requests.get(0);
        assertThat(request.method()).isEqualTo(HttpMethod.GET);
        assertThat(request.url().getRawQuery()).contains("%2B09%3A00").doesNotContain("%252B");
        assertThat(URLDecoder.decode(request.url().getRawQuery(), StandardCharsets.UTF_8))
                .contains("from=2026-09-29T00:00:00.000+09:00", "to=2026-09-29T23:59:59.999+09:00",
                        "rangeType=ORDERED_DATETIME", "quantityClaimCompatibility=true", "pageSize=50", "page=2", "productOrderStatuses=PAYED")
                .doesNotContain("channel", "page_size", "product_order");
        assertThat(request.headers().getFirst("Authorization")).isEqualTo("Bearer " + TOKEN);
    }

    @Test void missingStatusMeansAllProviderStatusesAndAlternativeDateBasisIsPreserved() {
        respond(HttpStatus.OK, "{\"data\":{}}");
        client.orders("app", TOKEN, FROM, TO, "CLAIM_REQUESTED_DATETIME", null, 1, 300, deadline());
        assertThat(requests.get(0).url().getQuery()).contains("rangeType=CLAIM_REQUESTED_DATETIME").doesNotContain("productOrderStatuses");
    }

    @Test void detailIsReadOnlyPostWithQuantityCompatibilityAndFullEnvelope() {
        respond(HttpStatus.OK, "{\"timestamp\":\"now\",\"data\":[{\"productOrder\":{\"productOrderId\":\"" + ID + "\"}}]}");
        var result = client.detail("app", TOKEN, ID, deadline());
        assertThat(result.path("data").get(0).path("productOrder").path("productOrderId").asString()).isEqualTo(ID);
        assertRequest(0, HttpMethod.POST, ORDERS + "/query");
        assertThat(body(0)).isEqualTo(json.readTree("{\"productOrderIds\":[\"" + ID + "\"],\"quantityClaimCompatibility\":true}"));
    }

    @Test void settlementUsesScheduledDateParametersAndPreservesSignedAndNullAmounts() {
        respond(HttpStatus.OK, "{\"elements\":[{\"settleAmount\":-1000,\"commissionSettleAmount\":null}],\"pagination\":{\"page\":3}}");
        var result = client.settlement("app", TOKEN, LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-28"), 3, 100, deadline());
        assertThat(result.path("elements").get(0).path("settleAmount").asInt()).isEqualTo(-1000);
        assertThat(result.path("elements").get(0).path("commissionSettleAmount").isNull()).isTrue();
        assertRequest(0, HttpMethod.GET, "/v1/pay-settle/settle/daily?startDate=2026-09-01&endDate=2026-09-28&pageNumber=3&pageSize=100");
    }

    @Test void invalidReadBoundsIdsAndTokensFailBeforeSending() {
        for (Runnable call : List.<Runnable>of(
                () -> client.orders("app", TOKEN, FROM, TO.plusDays(1), "PAYED_DATETIME", null, 1, 20, deadline()),
                () -> client.orders("app", TOKEN, TO, FROM, "PAYED_DATETIME", null, 1, 20, deadline()),
                () -> client.orders("app", TOKEN, FROM, TO, "BAD", null, 1, 20, deadline()),
                () -> client.orders("app", TOKEN, FROM, TO, "PAYED_DATETIME", "PAYED&all=true", 1, 20, deadline()),
                () -> client.orders("app", TOKEN, FROM, TO, "PAYED_DATETIME", null, 0, 20, deadline()),
                () -> client.orders("app", TOKEN, FROM, TO, "PAYED_DATETIME", null, 1, 301, deadline()),
                () -> client.detail("app", TOKEN, "../../other", deadline()),
                () -> client.detail("app", " ", ID, deadline()),
                () -> client.detail("app", TOKEN, "0", deadline()),
                () -> client.settlement("app", TOKEN, FROM.toLocalDate(), FROM.toLocalDate().plusMonths(1).plusDays(1), 1, 20, deadline()),
                () -> client.settlement("app", TOKEN, FROM.toLocalDate(), FROM.toLocalDate(), 1, 1001, deadline()))) {
            assertThatThrownBy(call::run).isInstanceOf(ApiException.class);
        }
        assertThat(requests).isEmpty();
    }

    @Test void confirmAndDispatchUseSingleItemProviderBodies() {
        respond(HttpStatus.OK, success());
        client.confirm(TOKEN, ID);
        assertRequest(0, HttpMethod.POST, ORDERS + "/confirm");
        assertThat(body(0)).isEqualTo(json.readTree("{\"productOrderIds\":[\"" + ID + "\"]}"));
        respond(HttpStatus.OK, success());
        client.dispatch(TOKEN, ID, dispatch());
        assertRequest(1, HttpMethod.POST, ORDERS + "/dispatch");
        var item = body(1).path("dispatchProductOrders").get(0);
        assertThat(item.path("productOrderId").asString()).isEqualTo(ID);
        assertThat(item.path("deliveryCompanyCode").asString()).isEqualTo("CJGLS");
        assertThat(item.path("trackingNumber").asString()).isEqualTo("123456789");
        assertThat(item.path("dispatchDate").asString()).isEqualTo("2026-09-29T10:00:00.000+09:00");
        assertThat(item.size()).isEqualTo(5);
    }

    @Test void approvalPostsHaveNoBodyClaimIdOrQuantity() {
        respond(HttpStatus.OK, success());
        client.approveCancel(TOKEN, ID);
        respond(HttpStatus.OK, success());
        client.approveReturn(TOKEN, ID);
        assertRequest(0, HttpMethod.POST, ORDERS + "/" + ID + "/claim/cancel/approve");
        assertRequest(1, HttpMethod.POST, ORDERS + "/" + ID + "/claim/return/approve");
        assertThat(written(0)).isEmpty();
        assertThat(written(1)).isEmpty();
    }

    @Test void allWriteEntryPointsValidateTheirOrderIdBeforeSending() {
        for (String id : List.of("", "0", "1?x=1", "../123", "123%2F456", "1\n2", "123456789012345678901")) {
            assertThatThrownBy(() -> client.confirm(TOKEN, id)).isInstanceOf(ApiException.class);
            assertThatThrownBy(() -> client.dispatch(TOKEN, id, dispatch())).isInstanceOf(ApiException.class);
            assertThatThrownBy(() -> client.approveCancel(TOKEN, id)).isInstanceOf(ApiException.class);
            assertThatThrownBy(() -> client.approveReturn(TOKEN, id)).isInstanceOf(ApiException.class);
        }
        assertThat(requests).isEmpty();
    }

    @Test void dispatchCannotOverrideOrderIdOrSendArbitraryFieldsAndRequiresCarrierTracking() {
        for (var invalid : List.of(
                Map.<String, Object>of("productOrderId", "123"),
                Map.<String, Object>of("deliveryMethod", "DELIVERY", "dispatchDate", FROM.toString()),
                Map.<String, Object>of("deliveryMethod", "RETURN_DELIVERY", "dispatchDate", FROM.toString()),
                Map.<String, Object>of("deliveryMethod", "NOTHING", "dispatchDate", "no date"),
                Map.<String, Object>of("deliveryMethod", "NOTHING", "dispatchDate", FROM.toString(), "trackingNumber", "123\n456"))) {
            assertThatThrownBy(() -> client.dispatch(TOKEN, ID, invalid)).isInstanceOf(ApiException.class);
        }
        assertThat(requests).isEmpty();
    }

    @Test void readonlyDetailCanRetryTransientErrorButWritesNeverReplay() {
        respond(HttpStatus.SERVICE_UNAVAILABLE, PRIVATE);
        respond(HttpStatus.OK, "{\"data\":[]}");
        client.detail("app", TOKEN, ID, deadline());
        assertThat(requests).hasSize(2);
        assertThat(body(1)).isEqualTo(body(0));
        respond(HttpStatus.SERVICE_UNAVAILABLE, PRIVATE);
        assertThatThrownBy(() -> client.approveCancel(TOKEN, ID)).isInstanceOf(NaverOrderClient.UnknownWrite.class).hasNoCause();
        assertThat(requests).hasSize(3);
    }

    @Test void onlyReadGatewayAuthenticationCanRequestTokenRefresh() {
        respond(HttpStatus.UNAUTHORIZED, "{\"code\":\"GW.AUTHN\"}");
        assertThatThrownBy(() -> client.detail("app", TOKEN, ID, deadline())).isInstanceOf(AuthenticationException.class);
        respond(HttpStatus.UNAUTHORIZED, "{\"code\":\"GW.AUTHN\"}");
        assertThatThrownBy(() -> client.confirm(TOKEN, ID)).isInstanceOf(ApiException.class).isNotInstanceOf(AuthenticationException.class);
        respond(HttpStatus.UNAUTHORIZED, "{\"code\":\"OTHER\"}");
        assertThatThrownBy(() -> client.detail("app", TOKEN, ID, deadline())).isInstanceOf(ApiException.class).isNotInstanceOf(AuthenticationException.class);
        assertThat(requests).hasSize(3);
    }

    @Test void providerErrorsAreMaskedAndIpConfigurationIsActionableWithoutEchoingIpOrBuyerData(CapturedOutput output) {
        for (boolean write : List.of(false, true)) {
            respond(HttpStatus.FORBIDDEN, "{\"code\":\"GW.IP_NOT_ALLOWED\",\"message\":\"" + PRIVATE + TOKEN + "\"}");
            assertThatThrownBy(() -> { if (write) client.confirm(TOKEN, ID); else client.detail("app", TOKEN, ID, deadline()); })
                    .isInstanceOf(ApiException.class).hasMessageContaining("허용 IP").hasNoCause()
                    .hasMessageNotContaining(PRIVATE).hasMessageNotContaining(TOKEN);
        }
        assertThat(requests).hasSize(2);
        assertThat(output.getAll()).doesNotContain(PRIVATE, TOKEN);
    }

    @Test void conclusiveWriteRejectionsNeverRetryAndRemainDistinctFromUnknownOutcomes() {
        for (var status : List.of(HttpStatus.BAD_REQUEST, HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND, HttpStatus.TOO_MANY_REQUESTS)) {
            int before = requests.size();
            respond(status, "{\"message\":\"" + PRIVATE + "\"}");
            assertThatThrownBy(() -> client.approveReturn(TOKEN, ID)).isInstanceOf(ApiException.class)
                    .isNotInstanceOf(NaverOrderClient.UnknownWrite.class).hasNoCause().hasMessageNotContaining(PRIVATE);
            assertThat(requests).hasSize(before + 1);
        }
    }

    @Test void timeoutsServerErrorsRedirectsAndUnparseableSuccessAreUnknownAndNeverRetried() {
        for (var status : List.of(HttpStatus.INTERNAL_SERVER_ERROR, HttpStatus.SERVICE_UNAVAILABLE,
                HttpStatus.REQUEST_TIMEOUT, HttpStatus.TEMPORARY_REDIRECT)) {
            int before = requests.size();
            respond(status, PRIVATE);
            assertThatThrownBy(() -> client.confirm(TOKEN, ID)).isInstanceOf(NaverOrderClient.UnknownWrite.class).hasNoCause();
            assertThat(requests).hasSize(before + 1);
        }
        for (var malformed : List.of("", "not-json " + PRIVATE, "null", "[]", "true")) {
            int before = requests.size();
            respond(HttpStatus.OK, malformed);
            assertThatThrownBy(() -> client.dispatch(TOKEN, ID, dispatch())).isInstanceOf(NaverOrderClient.UnknownWrite.class).hasNoCause();
            assertThat(requests).hasSize(before + 1);
        }
        responses.add(Mono.error(new TimeoutException(PRIVATE)));
        assertThatThrownBy(() -> client.approveCancel(TOKEN, ID)).isInstanceOf(NaverOrderClient.UnknownWrite.class)
                .hasNoCause().hasMessageNotContaining(PRIVATE);
    }

    @Test void roundQuotaStopsButSecondsQuotaCanRetryReadOnlyRequests() {
        responses.add(Mono.just(ClientResponse.create(HttpStatus.TOO_MANY_REQUESTS).header("GNCP-GW-Quota-Period", "ROUND")
                .body("{\"code\":\"GW.QUOTA_LIMIT\"}").build()));
        assertThatThrownBy(() -> client.detail("app", TOKEN, ID, deadline())).isInstanceOf(ApiException.class).hasMessageContaining("할당량");
        responses.add(Mono.just(ClientResponse.create(HttpStatus.TOO_MANY_REQUESTS).header("GNCP-GW-Quota-Period", "SECONDS")
                .header("Retry-After", "2").body("{\"code\":\"GW.QUOTA_LIMIT\"}").build()));
        respond(HttpStatus.OK, "{\"data\":[]}");
        client.detail("another-app", TOKEN, ID, deadline());
        assertThat(requests).hasSize(3);
    }

    @Test void byteArrayTransportDoesNotLogBuyerContentOrTrackingValuesAtSpringDebug(CapturedOutput output) {
        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("org.springframework");
        var previous = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        try {
            respond(HttpStatus.OK, "{\"data\":[{\"ordererName\":\"" + PRIVATE + "\"}]}");
            assertThat(client.detail("app", TOKEN, ID, deadline()).path("data").size()).isEqualTo(1);
            written(0);
            respond(HttpStatus.OK, success());
            client.dispatch(TOKEN, ID, dispatch());
            written(1);
            assertThat(output.getAll()).doesNotContain(PRIVATE, TOKEN, "123456789");
        } finally { logger.setLevel(previous); }
    }

    private Map<String, Object> dispatch() { return Map.of("deliveryMethod", "DELIVERY", "deliveryCompanyCode", "CJGLS",
            "trackingNumber", "123456789", "dispatchDate", "2026-09-29T01:00:00Z"); }
    private String success() { return "{\"data\":{\"successProductOrderIds\":[\"" + ID + "\"],\"failProductOrderInfos\":[]}}"; }
    private long deadline() { return clock.get() + Duration.ofSeconds(45).toNanos(); }
    private void respond(HttpStatus status, String body) { responses.add(Mono.just(ClientResponse.create(status)
            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE).body(body).build())); }
    private void assertRequest(int index, HttpMethod method, String path) {
        assertThat(requests.get(index).method()).isEqualTo(method);
        assertThat(requests.get(index).url().toString()).isEqualTo(BASE + path);
    }
    private String written(int index) {
        var request = requests.get(index);
        var output = new MockClientHttpRequest(request.method(), request.url());
        request.writeTo(output, ExchangeStrategies.withDefaults()).block();
        return output.getBodyAsString().block();
    }
    private JsonNode body(int index) { return json.readTree(written(index)); }
}
