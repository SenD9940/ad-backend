package com.orinan.api.domain.platformconnection.naver;

import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.exception.ApiException;
import com.orinan.api.common.time.SeoulDateTimes;
import com.orinan.db.naverconnection.enums.NaverTokenType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.json.JacksonJsonDecoder;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class NaverCommerceClientTest {

    private static final String CLIENT_SECRET = "$2a$04$abcdefghijklmnopqrstuv";
    private static final String ACCESS_TOKEN = "stored-access-token-do-not-expose";
    private final List<ClientRequest> requests = new ArrayList<>();
    private final Queue<ClientResponse> responses = new ArrayDeque<>();
    private NaverCommerceClient client;
    private ExchangeStrategies strategies;

    @BeforeEach
    void setUp() {
        JsonMapper mapper = JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();
        strategies = ExchangeStrategies.builder().codecs(codecs -> codecs.defaultCodecs()
                .jacksonJsonDecoder(new JacksonJsonDecoder(mapper))).build();
        client = new NaverCommerceClient(WebClient.builder().exchangeStrategies(strategies)
                .exchangeFunction(request -> {
                    requests.add(request);
                    return Mono.just(responses.remove());
                }), mapper);
    }

    @Test
    void issuesSelfTokenWithBcryptSignatureInFormAndNoSecretInUrl() {
        token();
        LocalDateTime before = SeoulDateTimes.now();
        long beforeTimestamp = System.currentTimeMillis();

        NaverCommerceClient.IssuedToken token = client.issueToken("application-id", CLIENT_SECRET, NaverTokenType.SELF, null);

        assertThat(token.accessToken()).isEqualTo(ACCESS_TOKEN);
        assertThat(token.expiresAt()).isBetween(before.plusSeconds(10800), SeoulDateTimes.now().plusSeconds(10800));
        assertThat(token.toString()).doesNotContain(ACCESS_TOKEN);
        assertThat(requests).hasSize(1);
        ClientRequest request = requests.get(0);
        assertThat(request.method()).isEqualTo(HttpMethod.POST);
        assertThat(request.url()).isEqualTo(URI.create("https://api.commerce.naver.com/external/v1/oauth2/token"));
        assertThat(request.headers().getContentType()).isEqualTo(MediaType.APPLICATION_FORM_URLENCODED);
        assertThat(request.headers().containsHeader("Authorization")).isFalse();
        Map<String, String> form = form(request);
        assertThat(form).containsEntry("client_id", "application-id").containsEntry("grant_type", "client_credentials")
                .containsEntry("type", "SELF").doesNotContainKeys("account_id", "client_secret");
        long timestamp = Long.parseLong(form.get("timestamp"));
        assertThat(timestamp).isBetween(beforeTimestamp, System.currentTimeMillis());
        String signedHash = new String(Base64.getDecoder().decode(form.get("client_secret_sign")), StandardCharsets.UTF_8);
        assertThat(signedHash).isEqualTo(BCrypt.hashpw("application-id_" + timestamp, CLIENT_SECRET));
    }

    @Test
    void sellerTokenIncludesOnlyRequestedSellerInForm() {
        token();

        client.issueToken("application-id", CLIENT_SECRET, NaverTokenType.SELLER, "seller-uid");

        assertThat(form(requests.get(0))).containsEntry("type", "SELLER").containsEntry("account_id", "seller-uid");
        assertThat(requests.get(0).url().getQuery()).isNull();
    }

    @Test
    void debugCodecLoggingNeverExposesTokenOrUpstreamErrorBody(CapturedOutput output) {
        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("org.springframework");
        var previousLevel = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        try {
            token();
            assertThat(client.issueToken("application-id", CLIENT_SECRET, NaverTokenType.SELF, null).accessToken())
                    .isEqualTo(ACCESS_TOKEN);
            form(requests.get(0));
            response(HttpStatus.UNAUTHORIZED, "{\"code\":\"GW.AUTHN\",\"message\":\"" + ACCESS_TOKEN + "\"}");
            assertThatThrownBy(() -> client.getChannels(ACCESS_TOKEN))
                    .isInstanceOf(NaverCommerceClient.AuthenticationException.class);
            assertThat(output.getAll()).doesNotContain(ACCESS_TOKEN, CLIENT_SECRET);
        } finally {
            logger.setLevel(previousLevel);
        }
    }

    @Test
    void rejectsMissingSellerOrUnexpectedSelfAccountBeforeHttp() {
        assertThatThrownBy(() -> client.issueToken("id", CLIENT_SECRET, NaverTokenType.SELLER, null))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> client.issueToken("id", CLIENT_SECRET, NaverTokenType.SELF, "seller"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> client.issueToken("id", CLIENT_SECRET, null, null))
                .isInstanceOf(ApiException.class);
        assertThat(requests).isEmpty();
    }

    @Test
    void rejectsMalformedOrExpensiveBcryptSecretsAndTruncatedPasswordsBeforeHashing() {
        for (String secret : List.of("not-a-bcrypt-salt", "$2a$31$abcdefghijklmnopqrstuv", "$2a$03$abcdefghijklmnopqrstuv")) {
            assertThatThrownBy(() -> client.issueToken("id", secret, NaverTokenType.SELF, null))
                    .isInstanceOf(ApiException.class).hasNoCause().hasMessageNotContaining(secret);
        }
        assertThatThrownBy(() -> client.issueToken("한".repeat(25), CLIENT_SECRET, NaverTokenType.SELF, null))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> client.issueToken("", CLIENT_SECRET, NaverTokenType.SELF, null))
                .isInstanceOf(ApiException.class);
        assertThat(requests).isEmpty();
    }

    @Test
    void requiresValidTokenExpiryAndBearerType() {
        for (String body : List.of(
                "{\"access_token\":\"secret\",\"token_type\":\"Bearer\"}",
                "{\"access_token\":\"secret\",\"expires_in\":0,\"token_type\":\"Bearer\"}",
                "{\"access_token\":\"secret\",\"expires_in\":\"10800\",\"token_type\":\"Bearer\"}",
                "{\"access_token\":\"secret\",\"expires_in\":10800,\"token_type\":\"Other\"}")) {
            json(body);
            assertThatThrownBy(() -> client.issueToken("id", CLIENT_SECRET, NaverTokenType.SELF, null))
                    .isInstanceOf(ApiException.class).hasNoCause().hasMessageNotContaining("secret");
        }
    }

    @Test
    void parsesOfficialCamelCaseAccountUnderGlobalSnakeCaseMapper() {
        json("{\"accountId\":\"seller-id\",\"accountUid\":\"verified-uid\",\"grade\":\"POWER\"}");

        assertThat(client.getSellerAccount(ACCESS_TOKEN))
                .isEqualTo(new NaverCommerceClient.SellerAccount("seller-id", "verified-uid"));
        assertThat(requests.get(0).url().toString()).isEqualTo("https://api.commerce.naver.com/external/v1/seller/account");
        assertThat(requests.get(0).headers().getFirst("Authorization")).isEqualTo("Bearer " + ACCESS_TOKEN);
    }

    @Test
    void rejectsAccountWithoutVerifiedUid() {
        json("{\"accountId\":\"seller-id\"}");

        assertThatThrownBy(() -> client.getSellerAccount(ACCESS_TOKEN)).isInstanceOf(ApiException.class);
    }

    @Test
    void normalizesDocumentedChannelObjectToListWithoutFollowingItsUrl() {
        json("""
                {"channelNo":123456,"channelType":"STOREFARM","name":"스토어",
                 "url":"https://smartstore.naver.com/example","representativeImageUrl":null}
                """);

        assertThat(client.getChannels(ACCESS_TOKEN)).containsExactly(new NaverCommerceClient.Channel(
                123456, "STOREFARM", "스토어", "https://smartstore.naver.com/example"));
        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).url().toString()).isEqualTo("https://api.commerce.naver.com/external/v1/seller/channels");
        assertThat(requests.get(0).headers().getFirst("Authorization")).isEqualTo("Bearer " + ACCESS_TOKEN);
    }

    @Test
    void acceptsMultipleChannelsAndEmptyListsAndChecksNumericIds() {
        json("""
                [{"channelNo":1,"channelType":"STOREFARM","name":"스토어"},
                 {"channelNo":2,"channelType":"WINDOW","name":"윈도","url":null}]
                """);
        assertThat(client.getChannels(ACCESS_TOKEN)).containsExactly(
                new NaverCommerceClient.Channel(1, "STOREFARM", "스토어", null),
                new NaverCommerceClient.Channel(2, "WINDOW", "윈도", null));
        json("[]");
        assertThat(client.getChannels(ACCESS_TOKEN)).isEmpty();
        for (String invalid : List.of("\"1\"", "0", "-1", "1.5", "9223372036854775808")) {
            json("{\"channelNo\":" + invalid + ",\"channelType\":\"STOREFARM\",\"name\":\"스토어\"}");
            assertThatThrownBy(() -> client.getChannels(ACCESS_TOKEN)).isInstanceOf(ApiException.class);
        }
    }

    @Test
    void rejectsDuplicateIdsOrInvalidChannelShape() {
        json("""
                [{"channelNo":1,"channelType":"STOREFARM","name":"스토어"},
                 {"channelNo":1,"channelType":"WINDOW","name":"다른 이름"}]
                """);
        assertThatThrownBy(() -> client.getChannels(ACCESS_TOKEN)).isInstanceOf(ApiException.class);
        json("{\"channels\":[]}");
        assertThatThrownBy(() -> client.getChannels(ACCESS_TOKEN)).isInstanceOf(ApiException.class);
        json("{\"channelNo\":1,\"channelType\":\"UNKNOWN\",\"name\":\"스토어\"}");
        assertThatThrownBy(() -> client.getChannels(ACCESS_TOKEN)).isInstanceOf(ApiException.class);
    }

    @Test
    void exposesOnlyGatewayAuthFailureAsRefreshMarkerWithoutRetryingOrLeakingBody() {
        response(HttpStatus.UNAUTHORIZED, "{\"code\":\"GW.AUTHN\",\"message\":\"" + ACCESS_TOKEN + "\"}");

        assertThatThrownBy(() -> client.getChannels(ACCESS_TOKEN))
                .isInstanceOf(NaverCommerceClient.AuthenticationException.class)
                .hasNoCause().hasMessageNotContaining(ACCESS_TOKEN);
        assertThat(requests).hasSize(1);
        response(HttpStatus.UNAUTHORIZED, "{\"code\":\"UNAUTHORIZED\"}");
        assertThatThrownBy(() -> client.getChannels(ACCESS_TOKEN))
                .isInstanceOf(ApiException.class).isNotInstanceOf(NaverCommerceClient.AuthenticationException.class);
    }

    @Test
    void tokenErrorsNeverTriggerAccessTokenRetryAndRateLimitIsServerError() {
        response(HttpStatus.UNAUTHORIZED, "{\"code\":\"GW.AUTHN\",\"message\":\"" + CLIENT_SECRET + "\"}");
        assertThatThrownBy(() -> client.issueToken("id", CLIENT_SECRET, NaverTokenType.SELF, null))
                .isInstanceOf(ApiException.class).isNotInstanceOf(NaverCommerceClient.AuthenticationException.class)
                .hasNoCause().hasMessageNotContaining(CLIENT_SECRET);
        for (HttpStatus status : List.of(HttpStatus.TOO_MANY_REQUESTS, HttpStatus.SERVICE_UNAVAILABLE)) {
            response(status, "{\"message\":\"" + ACCESS_TOKEN + "\"}");
            assertThatThrownBy(() -> client.getChannels(ACCESS_TOKEN))
                    .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getCodeIfs()).isEqualTo(ApiCode.SERVER_ERROR))
                    .hasNoCause().hasMessageNotContaining(ACCESS_TOKEN);
        }
        assertThat(requests).hasSize(3);
    }

    @Test
    void rejectedIpExplainsTheRequiredSettingAndLogsOnlyAllowlistedDiagnostics(CapturedOutput output) {
        response(HttpStatus.FORBIDDEN, """
                {"code":"GW.IP_NOT_ALLOWED","message":"%s", "traceId":"private-trace-id",
                 "invalidInputs":[{"name":"client_secret_sign","value":"%s","message":"%s"},
                                  {"name":"private-field-name","value":"private-field-value"}]}
                """.formatted(ACCESS_TOKEN, CLIENT_SECRET, ACCESS_TOKEN));

        assertThatThrownBy(() -> client.issueToken("id", CLIENT_SECRET, NaverTokenType.SELF, null))
                .isInstanceOf(ApiException.class).isNotInstanceOf(NaverCommerceClient.AuthenticationException.class)
                .hasMessageContaining("API 호출 IP").hasMessageContaining("현재 서버의 공인 IP").hasNoCause()
                .hasMessageNotContaining(ACCESS_TOKEN).hasMessageNotContaining(CLIENT_SECRET);
        assertThat(output.getAll()).contains("operation=TOKEN http_status=403 code=GW.IP_NOT_ALLOWED",
                        "invalid_fields=[client_secret_sign]")
                .doesNotContain(ACCESS_TOKEN, CLIENT_SECRET, "private-trace-id", "private-field-name", "private-field-value");
        assertThat(requests).hasSize(1);
    }

    @Test
    void tokenValidationFieldsProduceStaticActionableMessagesWithoutRetrying(CapturedOutput output) {
        Map<String, String> expectedMessages = Map.of(
                "client_id", "애플리케이션 ID",
                "client_secret_sign", "인증 서명",
                "timestamp", "날짜·시간 자동 동기화",
                "type", "인증 유형",
                "account_id", "판매자 ID");
        expectedMessages.forEach((field, message) -> {
            response(HttpStatus.BAD_REQUEST, """
                    {"code":"BAD_REQUEST","message":"%s",
                     "invalidInputs":[{"name":"%s","value":"%s","message":"%s"}]}
                    """.formatted(ACCESS_TOKEN, field, CLIENT_SECRET, ACCESS_TOKEN));
            assertThatThrownBy(() -> client.issueToken("id", CLIENT_SECRET, NaverTokenType.SELF, null))
                    .isInstanceOf(ApiException.class).isNotInstanceOf(NaverCommerceClient.AuthenticationException.class)
                    .hasMessageContaining(message).hasNoCause()
                    .hasMessageNotContaining(ACCESS_TOKEN).hasMessageNotContaining(CLIENT_SECRET);
        });
        assertThat(requests).hasSize(expectedMessages.size());
        assertThat(output.getAll()).contains("operation=TOKEN http_status=400 code=BAD_REQUEST")
                .doesNotContain(ACCESS_TOKEN, CLIENT_SECRET);
    }

    @Test
    void unrecognizedProviderTextCannotEnterDiagnosticsOrInventFieldErrors(CapturedOutput output) {
        response(HttpStatus.BAD_REQUEST, """
                {"code":"%s","message":"timestamp client_secret_sign %s",
                 "invalidInputs":[{"name":"%s"},{"name":{"client_id":"%s"}},null]}
                """.formatted(ACCESS_TOKEN, CLIENT_SECRET, ACCESS_TOKEN, CLIENT_SECRET));
        assertThatThrownBy(() -> client.issueToken("id", CLIENT_SECRET, NaverTokenType.SELF, null))
                .isInstanceOf(ApiException.class).hasMessageContaining("인증 토큰 발급이 거절")
                .hasMessageNotContaining("날짜·시간 자동 동기화").hasMessageNotContaining("인증 서명이 거절").hasNoCause();
        assertThat(output.getAll()).contains("operation=TOKEN http_status=400 code=UNKNOWN invalid_fields=[]")
                .doesNotContain(ACCESS_TOKEN, CLIENT_SECRET);
    }

    @Test
    void unusableErrorBodiesKeepTheKnownHttpStatusAndSafeFallback(CapturedOutput output) {
        for (String body : List.of("", "invalid JSON " + ACCESS_TOKEN, "null", "[]", "42",
                "{\"code\":\"GW.AUTHN\",\"message\":\"" + ACCESS_TOKEN.repeat(1000) + "\"}")) {
            response(HttpStatus.UNAUTHORIZED, body);
            assertThatThrownBy(() -> client.getChannels(ACCESS_TOKEN))
                    .isInstanceOfSatisfying(ApiException.class,
                            error -> assertThat(error.getCodeIfs()).isEqualTo(ApiCode.BAD_REQUEST))
                    .isNotInstanceOf(NaverCommerceClient.AuthenticationException.class)
                    .hasMessageContaining("조회 요청이 거절").hasNoCause();
            response(HttpStatus.SERVICE_UNAVAILABLE, body);
            assertThatThrownBy(() -> client.issueToken("id", CLIENT_SECRET, NaverTokenType.SELF, null))
                    .isInstanceOfSatisfying(ApiException.class,
                            error -> assertThat(error.getCodeIfs()).isEqualTo(ApiCode.SERVER_ERROR))
                    .hasMessageContaining("요청이 지연").hasNoCause();
        }
        assertThat(output.getAll()).contains("operation=RESOURCE http_status=401 code=UNKNOWN",
                        "operation=TOKEN http_status=503 code=UNKNOWN")
                .doesNotContain(ACCESS_TOKEN, CLIENT_SECRET);
    }

    @Test
    void errorsWhileReadingTheBodyRetainStatusWithoutLeakingTheTransportCause(CapturedOutput output) {
        responses.add(ClientResponse.create(HttpStatus.FORBIDDEN, strategies)
                .body(reactor.core.publisher.Flux.error(new IllegalStateException(ACCESS_TOKEN))).build());
        assertThatThrownBy(() -> client.getChannels(ACCESS_TOKEN))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.getCodeIfs()).isEqualTo(ApiCode.BAD_REQUEST))
                .hasMessageContaining("조회 요청이 거절").hasNoCause();
        assertThat(output.getAll()).contains("operation=RESOURCE http_status=403 code=UNKNOWN")
                .doesNotContain(ACCESS_TOKEN);
    }

    @Test
    void tokenFieldHintsDoNotOverrideResourceErrorsOrProviderAvailability() {
        String body = """
                {"code":"BAD_REQUEST","invalidInputs":[{"name":"timestamp"}]}
                """;
        response(HttpStatus.BAD_REQUEST, body);
        assertThatThrownBy(() -> client.getChannels(ACCESS_TOKEN))
                .hasMessageContaining("조회 요청이 거절").hasMessageNotContaining("날짜·시간 자동 동기화");
        response(HttpStatus.TOO_MANY_REQUESTS, body);
        assertThatThrownBy(() -> client.issueToken("id", CLIENT_SECRET, NaverTokenType.SELF, null))
                .hasMessageContaining("요청이 지연").hasMessageNotContaining("날짜·시간 자동 동기화");
    }

    @Test
    void sanitizesTransportAndMalformedResponseFailures() {
        for (String body : List.of("", "invalid json containing " + ACCESS_TOKEN, "null", "42")) {
            json(body);
            assertThatThrownBy(() -> client.getChannels(ACCESS_TOKEN))
                    .isInstanceOf(ApiException.class).hasNoCause().hasMessageNotContaining(ACCESS_TOKEN);
        }
        NaverCommerceClient failing = new NaverCommerceClient(WebClient.builder().exchangeFunction(request ->
                Mono.error(new IllegalStateException("Bearer " + ACCESS_TOKEN))), JsonMapper.builder().build());
        assertThatThrownBy(() -> failing.getSellerAccount(ACCESS_TOKEN))
                .isInstanceOf(ApiException.class).hasNoCause().hasMessageNotContaining(ACCESS_TOKEN);
    }

    private void token() {
        json("{\"access_token\":\"" + ACCESS_TOKEN + "\",\"expires_in\":10800,\"token_type\":\"Bearer\"}");
    }

    private void json(String body) {
        response(HttpStatus.OK, body);
    }

    private void response(HttpStatus status, String body) {
        responses.add(ClientResponse.create(status, strategies)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE).body(body).build());
    }

    private Map<String, String> form(ClientRequest request) {
        MockClientHttpRequest output = new MockClientHttpRequest(request.method(), request.url());
        request.writeTo(output, ExchangeStrategies.withDefaults()).block();
        return Arrays.stream(output.getBodyAsString().block().split("&"))
                .map(pair -> pair.split("=", 2))
                .collect(Collectors.toMap(pair -> URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                        pair -> URLDecoder.decode(pair[1], StandardCharsets.UTF_8)));
    }
}
