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

import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.Instant;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class NaverStoreClientTest {
    private static final String TOKEN = "token-do-not-print";
    private static final OffsetDateTime FROM = OffsetDateTime.parse("2026-09-22T00:00:00+09:00");
    private static final OffsetDateTime TO = OffsetDateTime.parse("2026-09-22T23:59:59.999+09:00");
    private final Queue<ClientResponse> responses = new ArrayDeque<>();
    private final List<ClientRequest> requests = new ArrayList<>();
    private final JsonMapper mapper = JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();
    private NaverStoreClient client;
    private final AtomicLong now = new AtomicLong(1);

    @BeforeEach
    void setUp() {
        client = new NaverStoreClient(WebClient.builder().exchangeFunction(request -> {
            requests.add(request);
            return Mono.just(responses.remove());
        }), mapper, new NaverReadExecutor(now::get, () -> Instant.parse("2026-09-28T00:00:00Z"), nanos -> now.addAndGet(nanos)));
    }

    @Test
    void catalogUsesReadOnlySearchAndReturnsOnlyOnSaleProductsOfVerifiedType() {
        json(products("""
                {"originProductNo":100,"channelProducts":[
                  {"channelProductNo":1001,"channelServiceType":"STOREFARM","name":"상품","statusType":"SALE",
                   "channelProductDisplayStatusType":"ON","salePrice":20000,"discountedPrice":18000,"stockQuantity":3,
                   "representativeImage":{"url":"https://shop-phinf.pstatic.net/image.jpg"}},
                  {"channelProductNo":2001,"channelServiceType":"WINDOW","statusType":"SALE"},
                  {"channelProductNo":1002,"channelServiceType":"STOREFARM","statusType":"OUTOFSTOCK"}]}
                """, 1, 20, 45, 3));

        var result = client.searchProducts("app-id", TOKEN, "STOREFARM", 1, 20, deadline());

        assertThat(result.products()).containsExactly(new NaverStoreClient.Product("1001", "100", "상품", "SALE", "ON",
                new BigDecimal("20000"), new BigDecimal("18000"), 3L, "https://shop-phinf.pstatic.net/image.jpg"));
        assertThat(result.sellerTotalElements()).isEqualTo(45);
        assertThat(result.hasNext()).isTrue();
        assertThat(requests.get(0).method()).isEqualTo(HttpMethod.POST);
        assertThat(requests.get(0).url().toString()).isEqualTo("https://api.commerce.naver.com/external/v1/products/search");
        JsonNode request = body(requests.get(0));
        assertThat(request.path("productStatusTypes").get(0).asString()).isEqualTo("SALE");
        assertThat(request.path("page").asInt()).isEqualTo(1);
        assertThat(request.path("orderType").asString()).isEqualTo("NO");
        assertThat(request.has("channelNo")).isFalse();
        assertThat(requests.get(0).headers().getFirst("Authorization")).isEqualTo("Bearer " + TOKEN);
    }

    @Test
    void emptyFilteredCatalogPageStillRetainsSellerPagination() {
        json(products("{\"originProductNo\":100,\"channelProducts\":[{\"channelServiceType\":\"WINDOW\"}]}", 1, 20, 45, 3));
        assertThat(client.searchProducts("app-id", TOKEN, "STOREFARM", 1, 20, deadline())).satisfies(page -> {
            assertThat(page.products()).isEmpty();
            assertThat(page.hasNext()).isTrue();
        });
        json(products("", 1, 20, 0, 0));
        assertThat(client.searchProducts("app-id", TOKEN, "STOREFARM", 1, 20, deadline()).hasNext()).isFalse();
    }

    @Test
    void optionalCatalogValuesStayNullAndUnsafeImageIsDiscarded() {
        json(products("""
                {"originProductNo":100,"channelProducts":[{"channelProductNo":1001,"channelServiceType":"STOREFARM",
                 "name":"상품","statusType":"SALE","salePrice":0,"representativeImage":{"url":"javascript:alert(1)"}}]}
                """, 1, 20, 1, 1));
        assertThat(client.searchProducts("app-id", TOKEN, "STOREFARM", 1, 20, deadline()).products().get(0)).satisfies(product -> {
            assertThat(product.salePrice()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(product.stockQuantity()).isNull();
            assertThat(product.discountedPrice()).isNull();
            assertThat(product.imageUrl()).isNull();
        });
    }

    @Test
    void paymentQueryUsesInclusiveSeoulWindowAllStatusesAndStrictOffsetEncoding() {
        json(orders(order("1", "123", "PURCHASE_DECIDED", "\"initialQuantity\":2,\"remainQuantity\":1,\"initialPaymentAmount\":30000,\"remainPaymentAmount\":15000"), false));

        var result = client.getOrders("app-id", TOKEN, 123, FROM, TO, 1, 300, deadline());

        assertThat(result.orders()).containsExactly(new NaverStoreClient.OrderLine("1", "order-1", "product-1", "상품",
                FROM.plusHours(12), "PURCHASE_DECIDED", 2, 1L, new BigDecimal("30000"), new BigDecimal("15000")));
        assertThat(result.hasNext()).isFalse();
        assertThat(requests.get(0).method()).isEqualTo(HttpMethod.GET);
        String url = requests.get(0).url().toString();
        assertThat(url).contains("%2B09%3A00").doesNotContain("%252B", "productOrderStatuses", TOKEN);
        assertThat(URLDecoder.decode(url, StandardCharsets.UTF_8)).contains("from=2026-09-22T00:00:00.000+09:00",
                "to=2026-09-22T23:59:59.999+09:00", "rangeType=PAYED_DATETIME", "quantityClaimCompatibility=true", "pageSize=300", "page=1");
    }

    @Test
    void preservesNullableRemainingValuesAndUsesDocumentedInitialFieldAliases() {
        json(orders(order("1", "123", "CANCELED", "\"quantity\":2,\"totalPaymentAmount\":30000,\"remainPaymentAmount\":null"), false));
        var line = client.getOrders("app-id", TOKEN, 123, FROM, TO, 1, 300, deadline()).orders().get(0);
        assertThat(line.initialQuantity()).isEqualTo(2);
        assertThat(line.initialPaymentAmount()).isEqualByComparingTo("30000");
        assertThat(line.remainingQuantity()).isNull();
        assertThat(line.remainingPaymentAmount()).isNull();
    }

    @Test
    void excludesOtherChannelsBeforeProjectionAndDoesNotExposeBuyerData(CapturedOutput output) {
        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("org.springframework");
        var previous = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        try {
            String own = order("1", "123", "PAYED", "\"quantity\":1,\"totalPaymentAmount\":1200");
            own = own.replace("\"orderId\":\"order-1\"", "\"orderId\":\"order-1\",\"ordererName\":\"BUYER-PRIVATE-NAME\",\"ordererTel\":\"PRIVATE-PHONE\"");
            json(orders(own + ",{\"content\":{\"productOrder\":{\"merchantChannelId\":\"999\"}}}", false));
            var result = client.getOrders("app-id", TOKEN, 123, FROM, TO, 1, 300, deadline());
            assertThat(result.orders()).hasSize(1);
            assertThat(mapper.writeValueAsString(result)).doesNotContain("BUYER-PRIVATE-NAME", "PRIVATE-PHONE", "orderer");
            assertThat(output.getAll()).doesNotContain("BUYER-PRIVATE-NAME", "PRIVATE-PHONE", TOKEN);
        } finally {
            logger.setLevel(previous);
        }
    }

    @Test
    void validatesBoundsBeforeMakingRequests() {
        for (Runnable call : List.<Runnable>of(
                () -> client.getOrders("app-id", TOKEN, 123, FROM, FROM.plusDays(1).plusSeconds(1), 1, 300, deadline()),
                () -> client.getOrders("app-id", TOKEN, 123, TO, FROM, 1, 300, deadline()),
                () -> client.getOrders("app-id", TOKEN, 0, FROM, TO, 1, 300, deadline()),
                () -> client.getOrders("app-id", TOKEN, 123, FROM, TO, 0, 300, deadline()),
                () -> client.getOrders("app-id", TOKEN, 123, FROM, TO, 1, 301, deadline()),
                () -> client.getOrders("app-id", "", 123, FROM, TO, 1, 300, deadline()),
                () -> client.searchProducts("app-id", TOKEN, "AFFILIATE", 1, 20, deadline()),
                () -> client.searchProducts("app-id", TOKEN, "STOREFARM", 1, 501, deadline()))) {
            assertThatThrownBy(call::run).isInstanceOf(ApiException.class);
        }
        assertThat(requests).isEmpty();
    }

    @Test
    void requiredFinancialFieldsAndChannelIdentityFailClosedInsteadOfShowingZero() {
        for (String fields : List.of("\"quantity\":1", "\"quantity\":1,\"totalPaymentAmount\":\"1500\"",
                "\"quantity\":1,\"totalPaymentAmount\":-1", "\"totalPaymentAmount\":1500",
                "\"quantity\":1,\"totalPaymentAmount\":1500,\"remainPaymentAmount\":1.5")) {
            json(orders(order("1", "123", "PAYED", fields), false));
            assertThatThrownBy(() -> client.getOrders("app-id", TOKEN, 123, FROM, TO, 1, 300, deadline())).isInstanceOf(ApiException.class).hasNoCause();
        }
        json(orders("{\"content\":{\"productOrder\":{}}}", false));
        assertThatThrownBy(() -> client.getOrders("app-id", TOKEN, 123, FROM, TO, 1, 300, deadline())).isInstanceOf(ApiException.class);
    }

    @Test
    void rejectsDuplicateOrMismatchedOrderIdsAndOutOfWindowPayments() {
        String valid = order("1", "123", "PAYED", "\"quantity\":1,\"totalPaymentAmount\":1500");
        for (String rows : List.of(valid + "," + valid,
                valid.replaceFirst("\"productOrderId\":\"1\"", "\"productOrderId\":\"other\""),
                valid.replace("2026-09-22T12:00+09:00", "2026-09-23T12:00+09:00"))) {
            json(orders(rows, false));
            assertThatThrownBy(() -> client.getOrders("app-id", TOKEN, 123, FROM, TO, 1, 300, deadline())).isInstanceOf(ApiException.class);
        }
    }

    @Test
    void invalidPagingAndMalformedSuccessCannotBecomeEmptySuccess() {
        for (String body : List.of("{}", "{\"data\":{}}", orders("", true), orders("", false).replace("\"page\":1", "\"page\":2"))) {
            json(body);
            assertThatThrownBy(() -> client.getOrders("app-id", TOKEN, 123, FROM, TO, 1, 300, deadline())).isInstanceOf(ApiException.class);
        }
    }

    @Test
    void gatewayExpiryIsTheOnlyRefreshSignalAndNoRequestIsRetried() {
        respond(HttpStatus.UNAUTHORIZED, "{\"code\":\"GW.AUTHN\",\"message\":\"" + TOKEN + "\"}");
        assertThatThrownBy(() -> client.getOrders("app-id", TOKEN, 123, FROM, TO, 1, 300, deadline())).isInstanceOf(AuthenticationException.class)
                .hasNoCause().hasMessageNotContaining(TOKEN);
        for (HttpStatus status : List.of(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN, HttpStatus.BAD_REQUEST,
                HttpStatus.NOT_IMPLEMENTED, HttpStatus.PERMANENT_REDIRECT)) {
            respond(status, "{\"message\":\"" + TOKEN + "\"}");
            assertThatThrownBy(() -> client.searchProducts("app-id", TOKEN, "STOREFARM", 1, 20, deadline())).isInstanceOf(ApiException.class)
                    .isNotInstanceOf(AuthenticationException.class).hasNoCause().hasMessageNotContaining(TOKEN);
        }
        assertThat(requests).hasSize(6);
    }

    @Test
    void rateLimitedOrdersRetryTheSameWindowAndPageAfterTheProviderDelay() {
        responses.add(ClientResponse.create(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", "5").body("{\"code\":\"GW.RATE_LIMIT\"}").build());
        json(orders("", false));

        var result = client.getOrders("app-id", TOKEN, 123, FROM, TO, 1, 300, deadline());

        assertThat(result.orders()).isEmpty();
        assertThat(requests).hasSize(2);
        assertThat(requests.get(0)).isNotSameAs(requests.get(1));
        assertThat(requests.get(1).url()).isEqualTo(requests.get(0).url());
        assertThat(requests).allSatisfy(request -> {
            assertThat(request.method()).isEqualTo(HttpMethod.GET);
            assertThat(request.headers().getFirst("Authorization")).isEqualTo("Bearer " + TOKEN);
        });
        assertThat(now).hasValue(5_000_000_001L);
    }

    @Test
    void transientReadOnlySearchRebuildsTheSameBodyAndCanRecover() {
        respond(HttpStatus.SERVICE_UNAVAILABLE, "private upstream response");
        respond(HttpStatus.BAD_GATEWAY, "private upstream response");
        json(products("", 2, 20, 0, 0));

        var result = client.searchProducts("app-id", TOKEN, "STOREFARM", 2, 20, deadline());

        assertThat(result.products()).isEmpty();
        assertThat(requests).hasSize(3);
        assertThat(requests.get(0)).isNotSameAs(requests.get(1));
        assertThat(requests).allSatisfy(request -> {
            assertThat(request.method()).isEqualTo(HttpMethod.POST);
            assertThat(body(request)).isEqualTo(body(requests.get(0)));
            assertThat(body(request).path("page").asInt()).isEqualTo(2);
        });
        assertThat(now).hasValue(3_000_000_001L);
    }

    @Test
    void exhaustedRetriesExposeOnlySafeStatusAndReason(CapturedOutput output) {
        for (int attempt = 0; attempt < 3; attempt++) {
            respond(HttpStatus.SERVICE_UNAVAILABLE, "{\"message\":\"" + TOKEN + " PRIVATE-BUYER-NAME\"}");
        }
        assertThatThrownBy(() -> client.getOrders("private-app-id", TOKEN, 123, FROM, TO, 1, 300, deadline()))
                .isInstanceOf(ApiException.class).hasNoCause().hasMessageContaining("네이버 서버")
                .hasMessageNotContaining(TOKEN).hasMessageNotContaining("PRIVATE-BUYER-NAME");
        assertThat(requests).hasSize(3);
        assertThat(output.getAll()).contains("status=503", "reason=UPSTREAM_SERVER")
                .doesNotContain(TOKEN, "PRIVATE-BUYER-NAME", "private-app-id");
    }

    @Test
    void roundAndUnknownQuotasStopWhileSecondsQuotaRetries() {
        for (String period : List.of("ROUND", "")) {
            var response = ClientResponse.create(HttpStatus.TOO_MANY_REQUESTS).body("{\"code\":\"GW.QUOTA_LIMIT\"}");
            if (!period.isEmpty()) response.header("GNCP-GW-Quota-Period", period);
            responses.add(response.build());
            assertThatThrownBy(() -> client.getOrders("app-id", TOKEN, 123, FROM, TO, 1, 300, deadline()))
                    .isInstanceOf(ApiException.class).hasMessageContaining("할당량");
        }
        assertThat(requests).hasSize(2);
        responses.add(ClientResponse.create(HttpStatus.TOO_MANY_REQUESTS)
                .header("GNCP-GW-Quota-Period", "SECONDS").body("{\"code\":\"GW.QUOTA_LIMIT\"}").build());
        json(orders("", false));
        assertThat(client.getOrders("app-id", TOKEN, 123, FROM, TO, 1, 300, deadline()).orders()).isEmpty();
        assertThat(requests).hasSize(4);
    }

    @Test
    void retryAfterBeyondTheReportDeadlineStopsWithoutAnotherRequest() {
        responses.add(ClientResponse.create(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", "Mon, 28 Sep 2026 00:01:00 GMT").body("{\"code\":\"GW.RATE_LIMIT\"}").build());
        assertThatThrownBy(() -> client.getOrders("app-id", TOKEN, 123, FROM, TO, 1, 300, deadline()))
                .isInstanceOf(ApiException.class).hasMessageContaining("요청량 제한");
        assertThat(requests).hasSize(1);
        assertThat(now).hasValue(1);
    }

    @Test
    void malformedSuccessIsNotRetriedAndDoesNotLeakThePayload(CapturedOutput output) {
        json("INVALID-JSON " + TOKEN + " PRIVATE-BUYER-NAME");
        assertThatThrownBy(() -> client.getOrders("app-id", TOKEN, 123, FROM, TO, 1, 300, deadline()))
                .isInstanceOf(ApiException.class).hasNoCause().hasMessageNotContaining(TOKEN)
                .hasMessageNotContaining("PRIVATE-BUYER-NAME");
        assertThat(requests).hasSize(1);
        assertThat(output.getAll()).doesNotContain(TOKEN, "PRIVATE-BUYER-NAME");
    }

    private String products(String contents, int page, int size, long total, int pages) {
        return "{\"contents\":[" + contents + "],\"page\":" + page + ",\"size\":" + size
                + ",\"totalElements\":" + total + ",\"totalPages\":" + pages + "}";
    }

    private String orders(String contents, boolean next) {
        return "{\"data\":{\"contents\":[" + contents + "],\"pagination\":{\"page\":1,\"size\":300,\"hasNext\":" + next + "}}}";
    }

    private String order(String id, String channel, String status, String amounts) {
        return "{\"productOrderId\":\"" + id + "\",\"content\":{\"order\":{\"orderId\":\"order-" + id
                + "\",\"paymentDate\":\"2026-09-22T12:00+09:00\"},\"productOrder\":{\"productOrderId\":\"" + id
                + "\",\"merchantChannelId\":\"" + channel + "\",\"productId\":\"product-" + id
                + "\",\"productName\":\"상품\",\"productOrderStatus\":\"" + status + "\"," + amounts + "}}}";
    }

    private JsonNode body(ClientRequest request) {
        MockClientHttpRequest output = new MockClientHttpRequest(request.method(), request.url());
        request.writeTo(output, ExchangeStrategies.withDefaults()).block();
        return mapper.readTree(output.getBodyAsString().block());
    }

    private long deadline() { return now.get() + Duration.ofSeconds(45).toNanos(); }

    private void json(String body) { respond(HttpStatus.OK, body); }
    private void respond(HttpStatus status, String body) {
        responses.add(ClientResponse.create(status).header("Content-Type", MediaType.APPLICATION_JSON_VALUE).body(body).build());
    }
}
