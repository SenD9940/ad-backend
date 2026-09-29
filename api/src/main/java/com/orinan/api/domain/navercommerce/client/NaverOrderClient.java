package com.orinan.api.domain.navercommerce.client;

import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.platformconnection.naver.NaverCommerceClient.AuthenticationException;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** Full provider envelopes stay inside the business boundary; no buyer data is logged here. */
@Component
public class NaverOrderClient {
    private static final String BASE = "https://api.commerce.naver.com/external";
    private static final String ORDERS = "/v1/pay-order/seller/product-orders";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSXXX");
    private static final Set<String> RANGES = Set.of("ORDERED_DATETIME", "PAYED_DATETIME", "DISPATCHED_DATETIME",
            "CLAIM_REQUESTED_DATETIME", "CLAIM_COMPLETED_DATETIME");
    private static final Set<String> STATUSES = Set.of("PAYMENT_WAITING", "PAYED", "DELIVERING", "DELIVERED",
            "PURCHASE_DECIDED", "EXCHANGED", "CANCELED", "RETURNED", "CANCELED_BY_NOPAYMENT");
    private static final Set<String> METHODS = Set.of("DELIVERY", "VISIT_RECEIPT", "DIRECT_DELIVERY", "QUICK_SVC", "NOTHING");
    private static final Set<String> DISPATCH_FIELDS = Set.of("deliveryMethod", "deliveryCompanyCode", "trackingNumber", "dispatchDate");
    private final WebClient http;
    private final WebClient writeHttp;
    private final JsonMapper json;
    private final NaverReadExecutor reads;

    public NaverOrderClient(WebClient.Builder builder, JsonMapper json, NaverReadExecutor reads) {
        // Automatic TCP replay and redirects can resend a claim or forward its bearer token.
        var connector = new ReactorClientHttpConnector(HttpClient.create().disableRetry(true).followRedirect(false));
        this.http = builder.clone().clientConnector(connector)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(8 * 1024 * 1024)).build();
        this.writeHttp = builder.clone().clientConnector(connector)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(8 * 1024 * 1024)).build();
        this.json = json;
        this.reads = reads;
    }

    public JsonNode orders(String app, String token, OffsetDateTime from, OffsetDateTime to,
                           String rangeType, String status, int page, int size, long deadline) {
        requireToken(token);
        if (from == null || to == null || from.isAfter(to) || Duration.between(from, to).compareTo(Duration.ofDays(1)) > 0
                || !RANGES.contains(rangeType == null ? "" : rangeType)
                || status != null && !STATUSES.contains(status) || page < 1 || size < 1 || size > 300) throw invalidRequest();
        var uri = UriComponentsBuilder.fromUriString(BASE + ORDERS)
                .queryParam("from", "{from}").queryParam("to", "{to}").queryParam("rangeType", rangeType)
                .queryParam("quantityClaimCompatibility", true).queryParam("pageSize", size).queryParam("page", page);
        if (status != null) uri.queryParam("productOrderStatuses", status);
        var target = uri.encode().buildAndExpand(Map.of("from", format(from), "to", format(to))).toUri();
        return read(app, deadline, () -> http.get().uri(target).headers(h -> h.setBearerAuth(token)));
    }

    public JsonNode detail(String app, String token, String productOrderId, long deadline) {
        requireToken(token);
        requireId(productOrderId);
        byte[] body = json.writeValueAsBytes(Map.of("productOrderIds", List.of(productOrderId), "quantityClaimCompatibility", true));
        return read(app, deadline, () -> http.post().uri(BASE + ORDERS + "/query")
                .headers(h -> h.setBearerAuth(token)).contentType(MediaType.APPLICATION_JSON).bodyValue(body));
    }

    /** Daily provider totals are account-wide; caller must first verify channel attribution. */
    public JsonNode settlement(String app, String token, LocalDate since, LocalDate until,
                               int page, int size, long deadline) {
        requireToken(token);
        if (since == null || until == null || since.isAfter(until) || until.isAfter(since.plusMonths(1))
                || page < 1 || size < 1 || size > 1000) throw invalidRequest();
        var uri = UriComponentsBuilder.fromUriString(BASE + "/v1/pay-settle/settle/daily")
                .queryParam("startDate", since).queryParam("endDate", until)
                .queryParam("pageNumber", page).queryParam("pageSize", size).build().toUri();
        return read(app, deadline, () -> http.get().uri(uri).headers(h -> h.setBearerAuth(token)));
    }

    public JsonNode confirm(String token, String productOrderId) {
        requireId(productOrderId);
        return write(token, ORDERS + "/confirm", Map.of("productOrderIds", List.of(productOrderId)));
    }

    public JsonNode dispatch(String token, String productOrderId, Map<String, Object> dispatchFields) {
        requireId(productOrderId);
        if (dispatchFields == null || !DISPATCH_FIELDS.containsAll(dispatchFields.keySet())) throw invalidRequest();
        String method = field(dispatchFields, "deliveryMethod", 30, true);
        if (!METHODS.contains(method)) throw invalidRequest();
        String carrier = field(dispatchFields, "deliveryCompanyCode", 100, "DELIVERY".equals(method));
        String tracking = field(dispatchFields, "trackingNumber", 100, "DELIVERY".equals(method));
        if (carrier != null && !carrier.matches("[A-Z0-9_]{1,100}")) throw invalidRequest();
        if (tracking != null && !tracking.matches("[A-Za-z0-9-]{1,100}")) throw invalidRequest();
        String date = field(dispatchFields, "dispatchDate", 50, true);
        OffsetDateTime at;
        try { at = OffsetDateTime.parse(date); } catch (RuntimeException exception) { throw invalidRequest(); }
        var item = new LinkedHashMap<String, Object>();
        item.put("productOrderId", productOrderId);
        item.put("deliveryMethod", method);
        if (carrier != null) item.put("deliveryCompanyCode", carrier);
        if (tracking != null) item.put("trackingNumber", tracking);
        item.put("dispatchDate", format(at));
        return write(token, ORDERS + "/dispatch", Map.of("dispatchProductOrders", List.of(item)));
    }

    public JsonNode approveCancel(String token, String productOrderId) {
        requireId(productOrderId);
        return write(token, ORDERS + "/" + productOrderId + "/claim/cancel/approve", null);
    }

    public JsonNode approveReturn(String token, String productOrderId) {
        requireId(productOrderId);
        return write(token, ORDERS + "/" + productOrderId + "/claim/return/approve", null);
    }

    private JsonNode read(String app, long deadline, Supplier<WebClient.RequestHeadersSpec<?>> request) {
        return reads.execute(app, NaverReadExecutor.Resource.ORDERS, deadline, timeout -> exchange(request, timeout, false));
    }

    private JsonNode write(String token, String path, Map<String, Object> payload) {
        requireToken(token);
        byte[] body = payload == null ? null : json.writeValueAsBytes(payload);
        return exchange(() -> {
            var request = writeHttp.post().uri(BASE + path).headers(h -> h.setBearerAuth(token));
            // Approval endpoints have no request body, including no invented claim ID or quantity.
            return body == null ? request : request.contentType(MediaType.APPLICATION_JSON).bodyValue(body);
        }, Duration.ofSeconds(30), true);
    }

    private JsonNode exchange(Supplier<WebClient.RequestHeadersSpec<?>> request, Duration timeout, boolean write) {
        try {
            var result = request.get().exchangeToMono(response -> {
                int status = response.statusCode().value();
                if (response.statusCode().is2xxSuccessful()) return response.bodyToMono(byte[].class).map(json::readTree);
                var retryAfter = reads.retryAfter(response.headers().asHttpHeaders().getFirst("Retry-After"));
                String period = response.headers().asHttpHeaders().getFirst("GNCP-GW-Quota-Period");
                return response.bodyToMono(byte[].class).defaultIfEmpty(new byte[0]).flatMap(bytes -> {
                    String code = "";
                    try { code = json.readTree(bytes).path("code").asString(""); } catch (RuntimeException ignored) { }
                    if (!write && status == 401 && "GW.AUTHN".equals(code)) return Mono.error(new AuthenticationException());
                    if (write && (status >= 500 || status == 408 || status >= 300 && status < 400)) return Mono.error(new UnknownWrite());
                    if ("GW.IP_NOT_ALLOWED".equals(code)) return Mono.error(new ApiException(ApiCode.BAD_REQUEST,
                            "네이버 커머스API에 서버의 외부 IP가 허용되어 있지 않습니다. 애플리케이션의 허용 IP 설정을 확인해 주세요."));
                    if (write) return Mono.error(rejected(status));
                    var quota = !"GW.QUOTA_LIMIT".equals(code) ? NaverReadExecutor.Quota.NONE
                            : "SECONDS".equals(period) ? NaverReadExecutor.Quota.SECONDS
                            : "ROUND".equals(period) ? NaverReadExecutor.Quota.ROUND : NaverReadExecutor.Quota.UNKNOWN;
                    return Mono.error(new NaverReadExecutor.UpstreamFailure(status, quota, retryAfter));
                });
            }).block(timeout);
            if (result == null || !result.isObject()) {
                if (write) throw new UnknownWrite();
                throw invalidResponse();
            }
            return result;
        } catch (ApiException | NaverReadExecutor.UpstreamFailure exception) { throw exception; }
        catch (RuntimeException exception) {
            if (write) throw new UnknownWrite();
            throw invalidResponse();
        }
    }

    private ApiException rejected(int status) {
        if (status == 401 || status == 403) return new ApiException(ApiCode.BAD_REQUEST,
                "네이버 주문 처리 권한과 연결 토큰을 확인해 주세요. 처리 요청은 자동으로 재시도하지 않았습니다.");
        if (status == 429) return new ApiException(ApiCode.BAD_REQUEST,
                "네이버 요청량 제한으로 주문 처리 요청이 거절되었습니다. 잠시 후 주문 상태를 다시 확인해 주세요.");
        return new ApiException(ApiCode.BAD_REQUEST,
                "네이버가 주문 처리 요청을 거절했습니다. 최신 주문 상태와 배송 정보를 확인해 주세요.");
    }

    private static String format(OffsetDateTime date) { return TIME.format(date.withOffsetSameInstant(ZoneOffset.ofHours(9))); }
    private void requireId(String id) { if (id == null || !id.matches("[1-9][0-9]{0,19}")) throw invalidRequest(); }
    private void requireToken(String token) {
        if (token == null || token.isBlank()) throw new ApiException(ApiCode.BAD_REQUEST, "네이버 스마트스토어를 다시 연결해 주세요.");
    }
    private String field(Map<String, Object> fields, String key, int max, boolean required) {
        Object value = fields.get(key);
        if (value == null && !required) return null;
        if (!(value instanceof String text) || text.isBlank() || text.length() > max) throw invalidRequest();
        return text;
    }
    private ApiException invalidRequest() { return new ApiException(ApiCode.BAD_REQUEST, "네이버 주문 번호, 조회 범위 또는 배송 정보를 확인해 주세요."); }
    private ApiException invalidResponse() { return new ApiException(ApiCode.SERVER_ERROR, "네이버 주문·정산 응답을 확인할 수 없습니다. 잠시 후 다시 조회해 주세요."); }
    public static final class UnknownWrite extends ApiException {
        public UnknownWrite() { super(ApiCode.SERVER_ERROR,
                "네이버 주문 처리 결과를 확정할 수 없습니다. 다시 처리하기 전에 최신 주문 상태와 스마트스토어센터를 확인해 주세요."); }
    }
}
