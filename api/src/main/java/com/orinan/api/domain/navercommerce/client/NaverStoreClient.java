package com.orinan.api.domain.navercommerce.client;

import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.platformconnection.naver.NaverCommerceClient.AuthenticationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/** Read-only catalog and order projection. Raw order payloads are never logged or returned. */
@Component
public class NaverStoreClient {
    private static final String BASE_URL = "https://api.commerce.naver.com/external";
    private static final ZoneOffset SEOUL = ZoneOffset.ofHours(9);
    private static final DateTimeFormatter NAVER_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSXXX");
    private final WebClient webClient;
    private final JsonMapper jsonMapper;
    private final NaverReadExecutor reads;

    public NaverStoreClient(WebClient.Builder builder, JsonMapper jsonMapper) {
        this(builder, jsonMapper, new NaverReadExecutor());
    }

    @Autowired
    public NaverStoreClient(WebClient.Builder builder, JsonMapper jsonMapper, NaverReadExecutor reads) {
        this.webClient = builder.clone().codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(8 * 1024 * 1024)).build();
        this.jsonMapper = jsonMapper;
        this.reads = reads;
    }

    /**
     * Naver searches the seller's catalog, not a channel number. Callers must establish that
     * the requested channel is the only accessible channel of this type before using this projection.
     * Pagination totals remain seller totals because a page can also contain other channel types.
     */
    public ProductPage searchProducts(String applicationId, String accessToken, String channelType, int page, int size, long deadlineNanos) {
        requireToken(accessToken);
        if (!Set.of("STOREFARM", "WINDOW").contains(channelType == null ? "" : channelType)
                || page < 1 || size < 1 || size > 500) {
            throw invalidRequest();
        }
        JsonNode response = read(applicationId, NaverReadExecutor.Resource.PRODUCTS, deadlineNanos,
                () -> webClient.post().uri(BASE_URL + "/v1/products/search")
                .headers(headers -> headers.setBearerAuth(accessToken)).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("page", page, "size", size, "productStatusTypes", List.of("SALE"), "orderType", "NO")));
        JsonNode contents = array(response, "contents", size);
        int returnedPage = positiveInt(response, "page");
        int returnedSize = positiveInt(response, "size");
        long total = nonnegativeLong(response, "totalElements");
        long pages = nonnegativeLong(response, "totalPages");
        if (returnedPage != page || returnedSize != size || pages > Integer.MAX_VALUE
                || total == 0 && !contents.isEmpty()) {
            throw invalidResponse();
        }
        List<Product> products = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode origin : contents) {
            for (JsonNode product : array(origin, "channelProducts", 100)) {
                String type = requiredText(product, "channelServiceType", 30);
                if (!channelType.equals(type)) continue;
                if (!"SALE".equals(requiredText(product, "statusType", 30))) continue;
                String id = positiveNumberId(product, "channelProductNo");
                if (!seen.add(id)) throw invalidResponse();
                String originId = positiveNumberId(origin, "originProductNo");
                products.add(new Product(id, originId, requiredText(product, "name", 4000), "SALE",
                        optionalText(product, "channelProductDisplayStatusType", 30),
                        requiredMoney(product, "salePrice"), optionalMoney(product, "discountedPrice"),
                        optionalNonnegativeLong(product, "stockQuantity"), imageUrl(product.path("representativeImage"))));
            }
        }
        return new ProductPage(List.copyOf(products), returnedPage, returnedSize, total, (int) pages, page < pages);
    }

    /** One inclusive window of at most 24 hours. The caller handles bounded pagination and aggregation. */
    public OrderPage getOrders(String applicationId, String accessToken, long channelNo, OffsetDateTime from,
                               OffsetDateTime to, int page, int size, long deadlineNanos) {
        requireToken(accessToken);
        if (channelNo <= 0 || from == null || to == null || from.isAfter(to)
                || java.time.Duration.between(from, to).compareTo(java.time.Duration.ofHours(24)) > 0
                || page < 1 || size < 1 || size > 300) {
            throw invalidRequest();
        }
        // URI template expansion strictly encodes the '+' in +09:00 exactly once.
        URI uri = UriComponentsBuilder.fromUriString(BASE_URL + "/v1/pay-order/seller/product-orders")
                .queryParam("from", "{from}").queryParam("to", "{to}")
                .queryParam("rangeType", "PAYED_DATETIME").queryParam("quantityClaimCompatibility", true)
                .queryParam("pageSize", size).queryParam("page", page).encode()
                .buildAndExpand(Map.of("from", NAVER_TIME.format(from.withOffsetSameInstant(SEOUL)),
                        "to", NAVER_TIME.format(to.withOffsetSameInstant(SEOUL)))).toUri();
        JsonNode data = read(applicationId, NaverReadExecutor.Resource.ORDERS, deadlineNanos,
                () -> webClient.get().uri(uri).headers(headers -> headers.setBearerAuth(accessToken))).path("data");
        JsonNode contents = array(data, "contents", size);
        JsonNode pagination = data.path("pagination");
        int returnedPage = positiveInt(pagination, "page");
        int returnedSize = positiveInt(pagination, "size");
        if (returnedPage != page || returnedSize != size || !pagination.path("hasNext").isBoolean()
                || contents.isEmpty() && pagination.path("hasNext").asBoolean()) {
            throw invalidResponse();
        }
        List<OrderLine> orders = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode row : contents) {
            JsonNode content = row.path("content");
            JsonNode product = content.path("productOrder");
            if (!Long.toString(channelNo).equals(requiredText(product, "merchantChannelId", 150))) continue;
            JsonNode order = content.path("order");
            String id = requiredText(product, "productOrderId", 150);
            if (!id.equals(requiredText(row, "productOrderId", 150)) || !seen.add(id)) throw invalidResponse();
            OffsetDateTime paidAt;
            try {
                paidAt = OffsetDateTime.parse(requiredText(order, "paymentDate", 100));
            } catch (RuntimeException exception) {
                throw invalidResponse();
            }
            if (paidAt.isBefore(from) || paidAt.isAfter(to)) throw invalidResponse();
            Long initialQuantity = optionalNonnegativeLong(product, "initialQuantity");
            if (initialQuantity == null) initialQuantity = nonnegativeLong(product, "quantity");
            BigDecimal initialPayment = optionalMoney(product, "initialPaymentAmount");
            if (initialPayment == null) initialPayment = requiredMoney(product, "totalPaymentAmount");
            orders.add(new OrderLine(id, requiredText(order, "orderId", 150),
                    requiredText(product, "productId", 150), requiredText(product, "productName", 4000),
                    paidAt, requiredText(product, "productOrderStatus", 60), initialQuantity,
                    optionalNonnegativeLong(product, "remainQuantity"), initialPayment,
                    optionalMoney(product, "remainPaymentAmount")));
        }
        return new OrderPage(List.copyOf(orders), returnedPage, returnedSize, pagination.path("hasNext").asBoolean());
    }

    private JsonNode read(String applicationId, NaverReadExecutor.Resource resource, long deadlineNanos,
                          Supplier<WebClient.RequestHeadersSpec<?>> request) {
        return reads.execute(applicationId, resource, deadlineNanos, timeout -> readOnce(request.get(), timeout));
    }

    private JsonNode readOnce(WebClient.RequestHeadersSpec<?> request, java.time.Duration timeout) {
        try {
            JsonNode response = request.exchangeToMono(result -> {
                int status = result.statusCode().value();
                if (result.statusCode().is2xxSuccessful()) {
                    // Decoding JSON directly can print the whole order, including buyer data, at DEBUG.
                    return result.bodyToMono(byte[].class).map(this::parseResponse);
                }
                if (status == 401) {
                    return result.bodyToMono(byte[].class).map(this::parseResponse)
                            .flatMap(body -> Mono.<JsonNode>error("GW.AUTHN".equals(body.path("code").asString())
                                    ? new AuthenticationException() : new NaverReadExecutor.UpstreamFailure(status, NaverReadExecutor.Quota.NONE, null)))
                            .switchIfEmpty(Mono.error(new NaverReadExecutor.UpstreamFailure(status, NaverReadExecutor.Quota.NONE, null)));
                }
                var retryAfter = reads.retryAfter(result.headers().asHttpHeaders().getFirst("Retry-After"));
                String quotaPeriod = result.headers().asHttpHeaders().getFirst("GNCP-GW-Quota-Period");
                if (status == 429) {
                    return result.bodyToMono(byte[].class).defaultIfEmpty(new byte[0]).flatMap(bytes -> {
                        boolean quota = false;
                        try { quota = "GW.QUOTA_LIMIT".equals(jsonMapper.readTree(bytes).path("code").asString()); }
                        catch (RuntimeException ignored) { /* HTTP status still governs bounded retries. */ }
                        var kind = !quota ? NaverReadExecutor.Quota.NONE : "ROUND".equals(quotaPeriod) ? NaverReadExecutor.Quota.ROUND
                                : "SECONDS".equals(quotaPeriod) ? NaverReadExecutor.Quota.SECONDS : NaverReadExecutor.Quota.UNKNOWN;
                        return Mono.error(new NaverReadExecutor.UpstreamFailure(status, kind, retryAfter));
                    });
                }
                return result.releaseBody().then(Mono.error(new NaverReadExecutor.UpstreamFailure(status, NaverReadExecutor.Quota.NONE, retryAfter)));
            }).block(timeout);
            if (response == null || !response.isObject()) throw invalidResponse();
            return response;
        } catch (NaverReadExecutor.UpstreamFailure failure) {
            throw failure;
        } catch (ApiException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof TimeoutException || cause.getClass().getSimpleName().contains("TimeoutException")) {
                    throw new ApiException(ApiCode.SERVER_ERROR, "네이버 서버 응답 시간이 초과되었습니다. 잠시 후 다시 조회해 주세요.");
                }
            }
            throw new ApiException(ApiCode.SERVER_ERROR, "네이버 상품·판매 정보를 조회하지 못했습니다. 잠시 후 다시 시도해 주세요.");
        }
    }

    private JsonNode parseResponse(byte[] bytes) {
        try {
            return jsonMapper.readTree(bytes);
        } catch (RuntimeException exception) {
            throw invalidResponse();
        }
    }

    private void requireToken(String token) {
        if (!StringUtils.hasText(token)) throw new ApiException(ApiCode.BAD_REQUEST, "네이버 스마트스토어를 다시 연결해 주세요.");
    }

    private JsonNode array(JsonNode node, String field, int maxSize) {
        JsonNode value = node.path(field);
        if (!value.isArray() || value.size() > maxSize) throw invalidResponse();
        return value;
    }

    private String positiveNumberId(JsonNode node, String field) {
        long value = nonnegativeLong(node, field);
        if (value == 0) throw invalidResponse();
        return Long.toString(value);
    }

    private int positiveInt(JsonNode node, String field) {
        long value = nonnegativeLong(node, field);
        if (value < 1 || value > Integer.MAX_VALUE) throw invalidResponse();
        return (int) value;
    }

    private long nonnegativeLong(JsonNode node, String field) {
        Long value = optionalNonnegativeLong(node, field);
        if (value == null) throw invalidResponse();
        return value;
    }

    private Long optionalNonnegativeLong(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) return null;
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < 0) throw invalidResponse();
        return value.asLong();
    }

    private BigDecimal requiredMoney(JsonNode node, String field) {
        BigDecimal value = optionalMoney(node, field);
        if (value == null) throw invalidResponse();
        return value;
    }

    private BigDecimal optionalMoney(JsonNode node, String field) {
        Long value = optionalNonnegativeLong(node, field);
        return value == null ? null : BigDecimal.valueOf(value);
    }

    private String requiredText(JsonNode node, String field, int maxLength) {
        String value = optionalText(node, field, maxLength);
        if (!StringUtils.hasText(value)) throw invalidResponse();
        return value;
    }

    private String optionalText(JsonNode node, String field, int maxLength) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) return null;
        if (!value.isString() || value.asString().length() > maxLength) throw invalidResponse();
        return value.asString();
    }

    private String imageUrl(JsonNode image) {
        String value = optionalText(image, "url", 2048);
        if (value == null) return null;
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null ? value : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private ApiException invalidRequest() {
        return new ApiException(ApiCode.BAD_REQUEST, "네이버 조회 채널, 기간 및 페이지 범위를 확인해 주세요.");
    }

    private ApiException invalidResponse() {
        return new ApiException(ApiCode.SERVER_ERROR, "네이버 상품·판매 응답이 올바르지 않습니다. 잠시 후 다시 시도해 주세요.");
    }

    public record ProductPage(List<Product> products, int page, int size, long sellerTotalElements, int sellerTotalPages, boolean hasNext) {}
    public record Product(String productId, String originProductId, String name, String status, String displayStatus,
                          BigDecimal salePrice, BigDecimal discountedPrice, Long stockQuantity, String imageUrl) {}
    public record OrderPage(List<OrderLine> orders, int page, int size, boolean hasNext) {}
    public record OrderLine(String productOrderId, String orderId, String productId, String productName,
                            OffsetDateTime paymentDate, String status, long initialQuantity, Long remainingQuantity,
                            BigDecimal initialPaymentAmount, BigDecimal remainingPaymentAmount) {}
}
