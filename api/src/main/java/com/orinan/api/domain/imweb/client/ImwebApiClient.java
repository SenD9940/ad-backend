package com.orinan.api.domain.imweb.client;

import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.exception.ApiException;
import com.orinan.api.common.time.SeoulDateTimes;
import io.netty.channel.ChannelOption;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** Fixed-host OpenAPI transport. Neither authentication grants nor product writes are replayed. */
@Component
public class ImwebApiClient {
    private static final String BASE = "https://openapi.imweb.me";
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration WRITE_TIMEOUT = Duration.ofSeconds(30);
    private static final Set<String> PRODUCT_QUERY = Set.of("unitCode", "page", "limit", "prodName", "prodStatus", "categoryCode", "prodType");
    private static final Set<String> ORDER_QUERY = Set.of("unitCode", "page", "limit", "startWtime", "endWtime", "paymentStatus", "saleChannel", "includeOrderPending");
    private final WebClient http;
    private final JsonMapper json;
    private final ImwebProperties properties;

    public ImwebApiClient(WebClient.Builder builder, JsonMapper json, ImwebProperties properties) {
        // Reactor Netty otherwise retries an aborted TCP request once, even without retryWhen().
        this.http = builder.clone().clientConnector(new ReactorClientHttpConnector(HttpClient.create()
                        .disableRetry(true).followRedirect(false).option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                        .responseTimeout(WRITE_TIMEOUT)))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(MAX_RESPONSE_BYTES)).build();
        this.json = json;
        this.properties = properties;
    }

    public Token exchangeCode(String code) {
        properties.validate();
        requireValue(code, 4096);
        var fields = credentials();
        fields.add("grantType", "authorization_code");
        fields.add("redirectUri", properties.getRedirectUri());
        fields.add("code", code);
        return token(fields);
    }

    public Token refreshToken(String refreshToken) {
        properties.validate();
        requireValue(refreshToken, 32768);
        var fields = credentials();
        fields.add("grantType", "refresh_token");
        fields.add("refreshToken", refreshToken);
        return token(fields);
    }

    public void completeIntegration(String accessToken) {
        requireToken(accessToken);
        var data = exchange(() -> http.patch().uri(BASE + "/site-info/integration-complete")
                .headers(h -> h.setBearerAuth(accessToken)), Mode.INTEGRATION);
        requireTrue(data);
    }

    public JsonNode site(String token) {
        requireToken(token);
        return object(exchange(() -> http.get().uri(BASE + "/site-info").headers(h -> h.setBearerAuth(token)), Mode.READ));
    }

    public JsonNode unit(String token, String unitCode) {
        requireToken(token);
        if (unitCode == null || !unitCode.matches("[A-Za-z0-9_-]{1,100}")) throw invalidRequest();
        return object(exchange(() -> http.get().uri(BASE + "/site-info/unit/" + unitCode)
                .headers(h -> h.setBearerAuth(token)), Mode.READ));
    }

    /** Only catalog/order resources are accepted; callers cannot redirect the bearer token. */
    public JsonNode read(String token, String path, MultiValueMap<String, String> query) {
        requireToken(token);
        Set<String> permitted;
        if ("/products".equals(path)) permitted = PRODUCT_QUERY;
        else if ("/orders".equals(path)) permitted = ORDER_QUERY;
        else if ("/products/shop-categories".equals(path) || productPath(path)) permitted = Set.of("unitCode");
        else throw invalidRequest();
        var uri = uri(path, query, permitted);
        return exchange(() -> http.get().uri(uri).headers(h -> h.setBearerAuth(token)), Mode.READ);
    }

    public JsonNode multipart(String token, String path, HttpMethod method, MultiValueMap<String, ?> fields) {
        requireToken(token);
        boolean create = HttpMethod.POST.equals(method) && "/products".equals(path);
        boolean edit = HttpMethod.PATCH.equals(method) && productPath(path);
        boolean upload = HttpMethod.POST.equals(method) && path != null && path.matches("/products/[1-9][0-9]{0,19}/images");
        if ((!create && !edit && !upload) || fields == null || fields.isEmpty()) throw invalidRequest();
        return exchange(() -> http.method(method).uri(BASE + path).headers(h -> h.setBearerAuth(token))
                .contentType(MediaType.MULTIPART_FORM_DATA).bodyValue(fields), Mode.WRITE);
    }

    /** Publishing is a distinct JSON endpoint, not part of the product-description multipart patch. */
    public void updateProductStatus(String token, String productNo, String status) {
        requireToken(token);
        if (productNo == null || !productNo.matches("[1-9][0-9]{0,19}")
                || status == null || !Set.of("sale", "soldout", "nosale").contains(status)) throw invalidRequest();
        byte[] body = json.writeValueAsBytes(Map.of("status", status));
        var data = exchange(() -> http.patch().uri(BASE + "/products/" + productNo + "/status")
                .headers(h -> h.setBearerAuth(token)).contentType(MediaType.APPLICATION_JSON).bodyValue(body), Mode.WRITE);
        requireTrue(data);
    }

    private LinkedMultiValueMap<String, String> credentials() {
        var fields = new LinkedMultiValueMap<String, String>();
        fields.add("clientId", properties.getClientId());
        fields.add("clientSecret", properties.getClientSecret());
        return fields;
    }

    private Token token(MultiValueMap<String, String> fields) {
        var data = exchange(() -> http.post().uri(BASE + "/oauth2/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).bodyValue(fields), Mode.TOKEN);
        String access = text(data, "accessToken", 32768);
        String refresh = text(data, "refreshToken", 32768);
        var scope = data.path("scope");
        String scopes = null;
        if (!scope.isMissingNode() && !scope.isNull()) {
            var values = new LinkedHashSet<String>();
            if (scope.isString()) {
                String value = scope.asString();
                if (value.length() > 4096 || value.isBlank()) throw invalidResponse();
                for (String item : value.strip().split("\\s+")) addScope(values, item);
            } else if (scope.isArray() && scope.size() > 0 && scope.size() <= 100) {
                for (var item : scope) {
                    if (!item.isString()) throw invalidResponse();
                    addScope(values, item.asString());
                }
            } else throw invalidResponse();
            scopes = String.join(" ", values);
        }
        return new Token(access, refresh, SeoulDateTimes.now().plusHours(2), scopes);
    }

    private void addScope(Set<String> scopes, String value) {
        if (value == null || !value.matches("[a-z][a-z0-9-]{0,60}:(read|write)")) throw invalidResponse();
        scopes.add(value);
    }

    private URI uri(String path, MultiValueMap<String, String> query, Set<String> permitted) {
        var uri = UriComponentsBuilder.fromUriString(BASE + path);
        var variables = new LinkedHashMap<String, String>();
        if (query != null) {
            if (query.size() > permitted.size()) throw invalidRequest();
            for (var entry : query.entrySet()) {
                if (!permitted.contains(entry.getKey()) || entry.getValue() == null || entry.getValue().size() != 1)
                    throw invalidRequest();
                String value = entry.getValue().get(0);
                requireValue(value, 2048);
                String variable = "q" + variables.size();
                uri.queryParam(entry.getKey(), "{" + variable + "}");
                variables.put(variable, value);
            }
        }
        return uri.encode().buildAndExpand(variables).toUri();
    }

    private JsonNode exchange(Supplier<WebClient.RequestHeadersSpec<?>> request, Mode mode) {
        try {
            JsonNode data = request.get().exchangeToMono(response -> {
                int status = response.statusCode().value();
                return response.bodyToMono(byte[].class).defaultIfEmpty(new byte[0]).flatMap(bytes -> {
                    if (bytes.length > MAX_RESPONSE_BYTES) return Mono.error(invalidFor(mode));
                    JsonNode body = null;
                    try { body = json.readTree(bytes); } catch (RuntimeException ignored) { /* Never expose provider payloads. */ }
                    if (status >= 200 && status < 300) {
                        if (body == null || !body.isObject() || !body.path("statusCode").isIntegralNumber()
                                || body.path("statusCode").asInt() < 200 || body.path("statusCode").asInt() >= 300
                                || body.hasNonNull("errorCode") || body.path("data").isMissingNode() || body.path("data").isNull())
                            return Mono.error(invalidFor(mode));
                        return Mono.just(body.path("data"));
                    }
                    Integer error = errorCode(body);
                    if (mode == Mode.READ && status == 401 && error != null && Set.of(30101, 30102, 30105).contains(error))
                        return Mono.error(new AuthenticationException());
                    if (mode == Mode.INTEGRATION && status == 404 && Integer.valueOf(30128).equals(error))
                        return Mono.error(new IntegrationStateException());
                    if (mode.isWrite() && (status >= 500 || status == 408 || status >= 300 && status < 400))
                        return Mono.error(new UnknownWriteException());
                    return Mono.error(rejected(status, mode));
                });
            }).block(mode == Mode.READ ? READ_TIMEOUT : WRITE_TIMEOUT);
            if (data == null) throw invalidFor(mode);
            return data;
        } catch (ApiException exception) { throw exception; }
        catch (RuntimeException exception) {
            if (mode.isWrite()) throw new UnknownWriteException();
            throw new ApiException(ApiCode.SERVER_ERROR, mode == Mode.TOKEN
                    ? "아임웹 인증 응답을 확인하지 못했습니다. 연결을 다시 시작해 주세요."
                    : "아임웹 조회 요청을 완료하지 못했습니다. 잠시 후 다시 조회해 주세요.");
        }
    }

    private ApiException rejected(int status, Mode mode) {
        if (status == 401 || status == 403) return new ApiException(ApiCode.BAD_REQUEST,
                "아임웹 접근 권한이 없거나 인증이 만료되었습니다. 앱 권한을 확인하고 다시 연결해 주세요.");
        if (status == 429) return new ApiException(ApiCode.BAD_REQUEST,
                "아임웹 요청량 제한에 도달했습니다. 잠시 후 다시 시도해 주세요.");
        if (mode == Mode.TOKEN) return new ApiException(ApiCode.BAD_REQUEST,
                "아임웹 인증 요청이 거절되었습니다. 앱 설정을 확인하고 연결을 다시 시작해 주세요.");
        if (mode == Mode.INTEGRATION) return new ApiException(ApiCode.BAD_REQUEST,
                "아임웹 앱 연동을 완료하지 못했습니다. 아임웹에서 앱 사용 신청 상태를 확인해 주세요.");
        if (mode == Mode.WRITE) return new ApiException(ApiCode.BAD_REQUEST,
                "아임웹이 상품 변경 요청을 거절했습니다. 상품 정보와 앱의 상품 쓰기 권한을 확인해 주세요.");
        return new ApiException(ApiCode.SERVER_ERROR, "아임웹 상품·주문 정보를 조회하지 못했습니다. 잠시 후 다시 조회해 주세요.");
    }

    private Integer errorCode(JsonNode node) {
        if (node == null || !node.isObject()) return null;
        var value = node.path("errorCode");
        if (value.isIntegralNumber() && value.canConvertToInt()) return value.asInt();
        if (value.isString() && value.asString().matches("[0-9]{1,8}")) return Integer.valueOf(value.asString());
        return null;
    }

    private boolean productPath(String path) { return path != null && path.matches("/products/[1-9][0-9]{0,19}"); }
    private JsonNode object(JsonNode value) { if (!value.isObject()) throw invalidResponse(); return value; }
    private void requireTrue(JsonNode data) { if (!data.isBoolean() || !data.asBoolean()) throw new UnknownWriteException(); }
    private void requireToken(String token) {
        if (token == null || token.isBlank() || token.length() > 32768 || token.contains("\r") || token.contains("\n"))
            throw new ApiException(ApiCode.BAD_REQUEST, "아임웹을 다시 연결해 주세요.");
    }
    private void requireValue(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max) throw invalidRequest();
    }
    private String text(JsonNode value, String field, int max) {
        var node = value.path(field);
        if (!node.isString() || node.asString().isBlank() || node.asString().length() > max
                || node.asString().contains("\r") || node.asString().contains("\n")) throw invalidResponse();
        return node.asString();
    }
    private ApiException invalidRequest() { return new ApiException(ApiCode.BAD_REQUEST, "아임웹 요청 정보를 확인해 주세요."); }
    private ApiException invalidResponse() { return new ApiException(ApiCode.SERVER_ERROR, "아임웹 응답을 확인하지 못했습니다. 잠시 후 다시 시도해 주세요."); }
    private ApiException invalidFor(Mode mode) { return mode.isWrite() ? new UnknownWriteException() : invalidResponse(); }
    private enum Mode { READ, WRITE, TOKEN, INTEGRATION; boolean isWrite() { return this == WRITE || this == INTEGRATION; } }

    public record Token(String accessToken, String refreshToken, LocalDateTime expiresAt, String scopes) {
        @Override public String toString() { return "Token[accessToken=REDACTED, refreshToken=REDACTED, expiresAt=" + expiresAt + "]"; }
    }

    public static class AuthenticationException extends ApiException {
        public AuthenticationException() { super(ApiCode.BAD_REQUEST, "아임웹 인증이 만료되었습니다. 다시 연결해 주세요."); }
    }

    public static class IntegrationStateException extends ApiException {
        public IntegrationStateException() { super(ApiCode.BAD_REQUEST, "아임웹 앱의 연동 상태를 확인해 주세요."); }
    }

    public static class UnknownWriteException extends ApiException {
        public UnknownWriteException() {
            super(ApiCode.SERVER_ERROR, "아임웹 변경 결과를 확인하지 못했습니다. 다시 등록하기 전에 아임웹 관리자에서 결과를 확인해 주세요.");
        }
        @Override public synchronized Throwable fillInStackTrace() { return this; }
    }
}
