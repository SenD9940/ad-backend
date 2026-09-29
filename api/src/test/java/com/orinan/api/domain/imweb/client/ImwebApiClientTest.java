package com.orinan.api.domain.imweb.client;

import com.orinan.api.common.exception.ApiException;
import com.orinan.api.common.time.SeoulDateTimes;
import com.orinan.api.domain.imweb.client.ImwebApiClient.AuthenticationException;
import com.orinan.api.domain.imweb.client.ImwebApiClient.IntegrationStateException;
import com.orinan.api.domain.imweb.client.ImwebApiClient.UnknownWriteException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class ImwebApiClientTest {
    private static final String BASE = "https://openapi.imweb.me";
    private static final String TOKEN = "IMWEB-ACCESS-TOKEN-PRIVATE";
    private static final String REFRESH = "IMWEB-REFRESH-TOKEN-PRIVATE";
    private static final String SECRET = "IMWEB-SECRET-PRIVATE";
    private final Queue<Mono<ClientResponse>> responses = new ArrayDeque<>();
    private final List<ClientRequest> requests = new ArrayList<>();
    private final JsonMapper json = JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();
    private ImwebProperties properties;
    private ImwebApiClient client;

    @BeforeEach void setUp() {
        properties = new ImwebProperties();
        properties.setEnabled(true);
        properties.setClientId("app-client");
        properties.setClientSecret(SECRET);
        properties.setRedirectUri("http://localhost:8480/open-api/platform-connections/imweb/callback");
        properties.setFrontendRedirectUri("http://localhost:3400/settings/integrations/imweb/callback");
        client = new ImwebApiClient(WebClient.builder().exchangeFunction(request -> {
            requests.add(request);
            return responses.remove();
        }), json, properties);
    }

    @Test void exchangeUsesCamelCaseFormBodyKeepsSecretsOutOfUrlAndUsesSeoulExpiry() {
        ok(tokenBody("\"site-info:read product:read\""));
        var before = SeoulDateTimes.now();
        var token = client.exchangeCode("CODE+&=한글");
        assertThat(token.accessToken()).isEqualTo(TOKEN);
        assertThat(token.refreshToken()).isEqualTo(REFRESH);
        assertThat(token.scopes()).isEqualTo("site-info:read product:read");
        assertThat(Duration.between(before, token.expiresAt()).toSeconds()).isBetween(7200L, 7202L);
        assertThat(token.toString()).doesNotContain(TOKEN, REFRESH);
        var request = requests.get(0);
        assertThat(request.method()).isEqualTo(HttpMethod.POST);
        assertThat(request.url().toString()).isEqualTo(BASE + "/oauth2/token").doesNotContain(SECRET, "CODE");
        var written = written(request);
        assertThat(written.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_FORM_URLENCODED);
        assertThat(written.getBodyAsString().block()).contains("clientId=app-client", "clientSecret=" + SECRET,
                "grantType=authorization_code", "redirectUri=http%3A%2F%2Flocalhost%3A8480%2Fopen-api%2Fplatform-connections%2Fimweb%2Fcallback",
                "code=CODE%2B%26%3D%ED%95%9C%EA%B8%80")
                .doesNotContain("client_id", "grant_type", "redirect_uri");
    }

    @Test void refreshUsesTheRefreshGrantAndParsesArrayScopesWithoutInventingMissingScope() {
        ok(tokenBody("[\"product:read\",\"order:read\",\"product:read\"]"));
        var token = client.refreshToken(REFRESH);
        assertThat(token.scopes()).isEqualTo("product:read order:read");
        assertThat(written(requests.get(0)).getBodyAsString().block()).contains("grantType=refresh_token", "refreshToken=" + REFRESH)
                .doesNotContain("redirectUri", "code=");
        ok("{\"accessToken\":\"" + TOKEN + "\",\"refreshToken\":\"" + REFRESH + "\"}");
        assertThat(client.exchangeCode("code").scopes()).isNull();
    }

    @Test void tokenMissingFieldsMalformedScopesAndProviderErrorPayloadsNeverBecomeCredentials() {
        for (var body : List.of("{}", "{\"accessToken\":\"token\"}", tokenBody("[]"), tokenBody("[3]"),
                tokenBody("\" \""), tokenBody("true"), tokenBody("[\"product:read arbitrary\"]"), tokenBody("\"product:read?secret=1\""))) {
            int before = requests.size();
            ok(body);
            assertThatThrownBy(() -> client.exchangeCode("code")).isInstanceOf(ApiException.class).hasNoCause()
                    .hasMessageNotContaining(TOKEN).hasMessageNotContaining(REFRESH);
            assertThat(requests).hasSize(before + 1);
        }
        respond(HttpStatus.BAD_REQUEST, "{\"errorCode\":30124,\"message\":\"" + SECRET + "\"}");
        assertThatThrownBy(() -> client.exchangeCode("code")).isInstanceOf(ApiException.class).hasNoCause().hasMessageNotContaining(SECRET);
    }

    @Test void disabledConfigurationAndInvalidInputsFailWithoutSendingTraffic() {
        properties.setEnabled(false);
        assertThatThrownBy(() -> client.exchangeCode("code")).isInstanceOf(ApiException.class);
        properties.setEnabled(true);
        assertThatThrownBy(() -> client.exchangeCode(" ")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> client.refreshToken(null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> client.site("bad\r\nAuthorization: injected")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> client.unit(TOKEN, "../site-info")).isInstanceOf(ApiException.class);
        assertThat(requests).isEmpty();
    }

    @Test void identityRoutesUseBearerHeadersAndReturnOnlyTheEnvelopeData() {
        ok("{\"siteCode\":\"S20260929abc\",\"unitList\":[{\"unitCode\":\"u20260929abc\",\"name\":\"상점\",\"currency\":\"KRW\"}]}");
        assertThat(client.site(TOKEN).path("siteCode").asString()).isEqualTo("S20260929abc");
        assertRequest(0, HttpMethod.GET, "/site-info");
        ok("{\"siteCode\":\"S20260929abc\",\"unitCode\":\"u20260929abc\",\"primaryDomain\":\"shop.imweb.me\",\"isDefault\":\"Y\"}");
        assertThat(client.unit(TOKEN, "u20260929abc").path("primaryDomain").asString()).isEqualTo("shop.imweb.me");
        assertRequest(1, HttpMethod.GET, "/site-info/unit/u20260929abc");
    }

    @Test void readParametersAreEncodedOnceIncludingOffsetAndSearchPunctuation() {
        var query = new LinkedMultiValueMap<String, String>();
        query.add("page", "1"); query.add("limit", "20"); query.add("unitCode", "u123");
        query.add("prodStatus", "sale"); query.add("prodName", "여름 & +/?# 상품");
        ok("{\"list\":[],\"totalCount\":0}");
        assertThat(client.read(TOKEN, "/products", query).path("list").isArray()).isTrue();
        assertThat(requests.get(0).url().toString()).contains("prodName=%EC%97%AC%EB%A6%84%20%26%20%2B%2F%3F%23%20%EC%83%81%ED%92%88");
        ok("{\"list\":[]}");
        client.read(TOKEN, "/orders", query("startWtime", "2026-09-29T00:00:00+09:00"));
        assertThat(requests.get(1).url().toString()).contains("startWtime=2026-09-29T00%3A00%3A00%2B09%3A00");
    }

    @Test void pathInjectionUnsupportedRoutesAndUnsupportedQueryKeysNeverSendBearerCredentials() {
        for (var path : List.of("https://evil.example/products", "//evil.example", "/products?unitCode=other", "/products/../site-info",
                "/products/%2e%2e/site-info", "/products/0", "/products/123/status", "/orders/123", "/oauth2/token")) {
            assertThatThrownBy(() -> client.read(TOKEN, path, new LinkedMultiValueMap<>())).isInstanceOf(ApiException.class);
        }
        assertThatThrownBy(() -> client.read(TOKEN, "/orders", query("accessToken", "leak"))).isInstanceOf(ApiException.class);
        var query = query("unitCode", "u1"); query.add("unitCode", "u2");
        assertThatThrownBy(() -> client.read(TOKEN, "/products", query)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> client.multipart(TOKEN, "https://evil.example/products", HttpMethod.POST, fields())).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> client.multipart(TOKEN, "/products", HttpMethod.DELETE, fields())).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> client.updateProductStatus(TOKEN, "1/../../site-info", "sale")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> client.updateProductStatus(TOKEN, "1", "ACTIVE")).isInstanceOf(ApiException.class);
        assertThat(requests).isEmpty();
    }

    @Test void completeIntegrationRequiresTrueAndOnlyExactAlreadyIntegratedCodeAllowsRecovery() {
        ok("true"); client.completeIntegration(TOKEN);
        assertRequest(0, HttpMethod.PATCH, "/site-info/integration-complete");
        assertThat(written(requests.get(0)).getBodyAsString().block()).isEmpty();
        respond(HttpStatus.NOT_FOUND, "{\"errorCode\":30128,\"message\":\"private\"}");
        assertThatThrownBy(() -> client.completeIntegration(TOKEN)).isInstanceOf(IntegrationStateException.class);
        respond(HttpStatus.NOT_FOUND, "{\"errorCode\":40001}");
        assertThatThrownBy(() -> client.completeIntegration(TOKEN)).isInstanceOf(ApiException.class).isNotInstanceOf(IntegrationStateException.class);
        ok("false");
        assertThatThrownBy(() -> client.completeIntegration(TOKEN)).isInstanceOf(UnknownWriteException.class);
        assertThat(requests).hasSize(4);
    }

    @Test void onlyExactReadAuthenticationFailuresCanTriggerCallerRefresh() {
        for (int code : List.of(30101, 30102, 30105)) {
            respond(HttpStatus.UNAUTHORIZED, "{\"errorCode\":" + code + "}");
            assertThatThrownBy(() -> client.site(TOKEN)).isInstanceOf(AuthenticationException.class).hasNoCause();
        }
        respond(HttpStatus.UNAUTHORIZED, "{\"errorCode\":39999}");
        assertThatThrownBy(() -> client.site(TOKEN)).isInstanceOf(ApiException.class).isNotInstanceOf(AuthenticationException.class);
        respond(HttpStatus.FORBIDDEN, "{\"errorCode\":30103}");
        assertThatThrownBy(() -> client.site(TOKEN)).isInstanceOf(ApiException.class).isNotInstanceOf(AuthenticationException.class);
        respond(HttpStatus.UNAUTHORIZED, "{\"errorCode\":30102}");
        assertThatThrownBy(() -> client.multipart(TOKEN, "/products", HttpMethod.POST, fields()))
                .isInstanceOf(ApiException.class).isNotInstanceOf(AuthenticationException.class).isNotInstanceOf(UnknownWriteException.class);
        assertThat(requests).hasSize(6);
    }

    @Test void multipartPreservesBracketNamesAndFileBytesEvenWithSnakeCaseApplicationMapper() {
        ok("{\"prodNo\":123,\"prodCode\":\"p123\"}");
        var data = client.multipart(TOKEN, "/products", HttpMethod.POST, fields());
        assertThat(data.path("prodNo").asInt()).isEqualTo(123);
        assertRequest(0, HttpMethod.POST, "/products");
        var request = written(requests.get(0));
        assertThat(request.getHeaders().getContentType().isCompatibleWith(MediaType.MULTIPART_FORM_DATA)).isTrue();
        String body = request.getBodyAsString().block();
        assertThat(body).contains("name=\"productImages\"; filename=\"product.png\"", "Content-Type: image/png", "PNG-BYTES",
                "name=\"productBaseInfo[unitShopProductInfo][0][productName]\"", "판매 상품", "name=\"productBaseInfo[status]\"", "nosale")
                .doesNotContain("product_base_info", TOKEN);
        var patch = new MultipartBodyBuilder();
        patch.part("unitCode", "u1"); patch.part("description", "<p>상세 설명</p>");
        ok("true");
        client.multipart(TOKEN, "/products/123", HttpMethod.PATCH, patch.build());
        assertRequest(1, HttpMethod.PATCH, "/products/123");
        assertThat(written(requests.get(1)).getBodyAsString().block()).contains("name=\"description\"", "<p>상세 설명</p>");
    }

    @Test void publishingUsesTheDedicatedJsonStatusEndpoint() {
        ok("true"); client.updateProductStatus(TOKEN, "123", "sale");
        assertRequest(0, HttpMethod.PATCH, "/products/123/status");
        var request = written(requests.get(0));
        assertThat(request.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(request.getBodyAsString().block()).isEqualTo("{\"status\":\"sale\"}");
        ok("false");
        assertThatThrownBy(() -> client.updateProductStatus(TOKEN, "123", "sale")).isInstanceOf(UnknownWriteException.class);
    }

    @Test void transientReadsFailWithoutRetriesOrFabricatedEmptyResults() {
        for (var status : List.of(HttpStatus.TOO_MANY_REQUESTS, HttpStatus.SERVICE_UNAVAILABLE, HttpStatus.FOUND)) {
            respond(status, "{\"message\":\"private upstream message\"}");
            assertThatThrownBy(() -> client.read(TOKEN, "/orders", query("page", "1"))).isInstanceOf(ApiException.class)
                    .hasNoCause().hasMessageNotContaining("private upstream message");
        }
        responses.add(Mono.error(new TimeoutException("private timeout")));
        assertThatThrownBy(() -> client.site(TOKEN)).isInstanceOf(ApiException.class).hasNoCause();
        assertThat(requests).hasSize(4);
    }

    @Test void ambiguousWritesHaveDistinctErrorsAndAreNeverAutomaticallyRepeated() {
        for (var status : List.of(HttpStatus.REQUEST_TIMEOUT, HttpStatus.INTERNAL_SERVER_ERROR, HttpStatus.SERVICE_UNAVAILABLE, HttpStatus.TEMPORARY_REDIRECT)) {
            respond(status, "{\"message\":\"" + TOKEN + " private error\"}");
            assertUnknownWrite();
        }
        responses.add(Mono.error(new TimeoutException("private timeout " + TOKEN))); assertUnknownWrite();
        responses.add(Mono.error(new IllegalStateException("private transport " + TOKEN))); assertUnknownWrite();
        for (var body : List.of("", "not json", "{}", "{\"statusCode\":200}", "{\"statusCode\":200,\"data\":null}",
                "{\"statusCode\":\"200\",\"data\":{}}", "{\"statusCode\":200,\"errorCode\":1,\"data\":{}}")) {
            respond(HttpStatus.OK, body); assertUnknownWrite();
        }
        assertThat(requests).hasSize(13);
    }

    @Test void knownWriteRejectionsAreNotConfusedWithUnknownOutcomes() {
        for (var status : List.of(HttpStatus.BAD_REQUEST, HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND, HttpStatus.TOO_MANY_REQUESTS)) {
            respond(status, "{\"errorCode\":10001,\"message\":\"" + SECRET + "\"}");
            assertThatThrownBy(() -> client.multipart(TOKEN, "/products", HttpMethod.POST, fields()))
                    .isInstanceOf(ApiException.class).isNotInstanceOf(UnknownWriteException.class)
                    .hasNoCause().hasMessageNotContaining(SECRET);
        }
        assertThat(requests).hasSize(4);
    }

    @Test void oversizedAndMalformedReadEnvelopesAreRejected() {
        for (var body : List.of("{}", "null", "{\"statusCode\":200,\"data\":null}", "{\"statusCode\":500,\"data\":{}}")) {
            respond(HttpStatus.OK, body);
            assertThatThrownBy(() -> client.site(TOKEN)).isInstanceOf(ApiException.class).hasNoCause();
        }
        ok("{\"large\":\"" + "x".repeat(4 * 1024 * 1024) + "\"}");
        assertThatThrownBy(() -> client.site(TOKEN)).isInstanceOf(ApiException.class).hasNoCause();
        assertThat(requests).hasSize(5);
    }

    @Test void credentialsAndOrderPersonalDataAreAbsentFromDebugLogsAndExceptions(CapturedOutput output) {
        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("org.springframework");
        var previous = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        try {
            ok(tokenBody("\"order:read\"")); client.exchangeCode("PRIVATE-CODE"); written(requests.get(0)).getBodyAsString().block();
            ok("{\"list\":[{\"orderNo\":1,\"ordererName\":\"PRIVATE-BUYER\",\"ordererCall\":\"PRIVATE-PHONE\"}]}");
            client.read(TOKEN, "/orders", query("page", "1"));
            respond(HttpStatus.BAD_REQUEST, "{\"errorCode\":10001,\"message\":\"" + SECRET + " PRIVATE-BUYER\"}");
            assertThatThrownBy(() -> client.site(TOKEN)).hasMessageNotContaining(SECRET).hasMessageNotContaining("PRIVATE-BUYER").hasNoCause();
            assertThat(output.getAll()).doesNotContain(TOKEN, REFRESH, SECRET, "PRIVATE-CODE", "PRIVATE-BUYER", "PRIVATE-PHONE");
        } finally { logger.setLevel(previous); }
    }

    private void assertUnknownWrite() {
        assertThatThrownBy(() -> client.multipart(TOKEN, "/products", HttpMethod.POST, fields()))
                .isInstanceOf(UnknownWriteException.class).hasNoCause().hasMessageNotContaining(TOKEN)
                .satisfies(error -> assertThat(error.getStackTrace()).isEmpty());
    }
    private MultiValueMap<String, ?> fields() {
        var fields = new MultipartBodyBuilder();
        fields.part("productImages", "PNG-BYTES".getBytes(StandardCharsets.UTF_8)).filename("product.png").contentType(MediaType.IMAGE_PNG);
        fields.part("productBaseInfo[status]", "nosale");
        fields.part("productBaseInfo[unitShopProductInfo][0][productName]", "판매 상품");
        return fields.build();
    }
    private LinkedMultiValueMap<String, String> query(String name, String value) {
        var query = new LinkedMultiValueMap<String, String>(); query.add(name, value); return query;
    }
    private String tokenBody(String scope) { return "{\"accessToken\":\"" + TOKEN + "\",\"refreshToken\":\"" + REFRESH + "\",\"scope\":" + scope + "}"; }
    private void assertRequest(int index, HttpMethod method, String path) {
        var request = requests.get(index);
        assertThat(request.method()).isEqualTo(method);
        assertThat(request.url().toString()).isEqualTo(BASE + path).doesNotContain(TOKEN);
        assertThat(request.headers().getFirst("Authorization")).isEqualTo("Bearer " + TOKEN);
    }
    private MockClientHttpRequest written(ClientRequest request) {
        var output = new MockClientHttpRequest(request.method(), request.url());
        request.writeTo(output, ExchangeStrategies.withDefaults()).block(); return output;
    }
    private void ok(String data) { respond(HttpStatus.OK, "{\"statusCode\":200,\"data\":" + data + "}"); }
    private void respond(HttpStatus status, String body) {
        responses.add(Mono.just(ClientResponse.create(status).header("Content-Type", MediaType.APPLICATION_JSON_VALUE).body(body).build()));
    }
}
